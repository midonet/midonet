/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import akka.actor.ActorSystem
import akka.dispatch.{ExecutionContext, Future, Promise}
import akka.dispatch.Future.flow
import akka.event.Logging
import scala.collection.{Map => ROMap}
import java.util.UUID

import com.midokura.midolman.rules.RuleResult.Action
import com.midokura.midolman.simulation.Coordinator._
import com.midokura.midolman.topology.{MacFlowCount,
                                       RemoveFlowCallbackGenerator}
import com.midokura.midonet.cluster.client.MacLearningTable
import com.midokura.packets.{ARP, Ethernet, IntIPv4, MAC}
import com.midokura.sdn.flows.{PacketMatch, WildcardMatch}
import com.midokura.util.functors.Callback1


class Bridge(val id: UUID, val greKey: Long,
             val macPortMap: MacLearningTable,
             val flowCount: MacFlowCount, val inFilter: Chain,
             val outFilter: Chain,
             val flowRemovedCallbackGen: RemoveFlowCallbackGenerator,
             val rtrMacToLogicalPortId: ROMap[MAC, UUID],
             val rtrIpToMac: ROMap[IntIPv4, MAC])
            (implicit val actorSystem: ActorSystem) extends Device {

    val log = Logging(actorSystem, this.getClass)
    log.info("Bridge being built.")

    override def process(ingressMatch: WildcardMatch, packet: Ethernet,
                         packetContext: PacketContext, expiry: Long)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem)
            : Future[Coordinator.Action] = {
        log.info("Bridge's process method called.")
        // Drop the packet if its L2 source is a multicast address.
        if (Ethernet.isMcast(ingressMatch.getEthernetSource)) {
            log.info("Bridge dropping a packet with a multi/broadcast source")
            return Promise.successful(DropAction())
        }
        else
            normalProcess(ingressMatch, packet, packetContext, expiry)
    }

    def normalProcess(ingressMatch: WildcardMatch, packet: Ethernet,
                      packetContext: PacketContext, expiry: Long)
                      (implicit ec: ExecutionContext)
    : Future[Coordinator.Action] = {
        val srcDlAddress = ingressMatch.getEthernetSource
        val dstDlAddress = ingressMatch.getEthernetDestination

        // Call ingress (pre-bridging) chain
        packetContext.inPortID = ingressMatch.getInputPortUUID
        packetContext.outPortID = null
        val preBridgeResult = Chain.apply(inFilter, packetContext, ingressMatch,
                                          id, false)
        log.info("The ingress chain returned {}", preBridgeResult)

        if (preBridgeResult.action == Action.DROP ||
                preBridgeResult.action == Action.REJECT) {
            // TOOD: Do something more for REJECT?
            learnMacOnPort(srcDlAddress, ingressMatch.getInputPortUUID,
                           packetContext)
            return Promise.successful(DropAction())
        } else if (preBridgeResult.action != Action.ACCEPT) {
            log.error("Pre-bridging for {} returned an action which was {}, " +
                      "not ACCEPT, DROP, or REJECT.", id,
                      preBridgeResult.action)
            return Promise.successful(ErrorDropAction())
        }
        if (!(preBridgeResult.pmatch.isInstanceOf[WildcardMatch])) {
            log.error("Pre-bridging for {} returned a match which was {}, " +
                      "not a WildcardMatch.", id, preBridgeResult.pmatch)
            return Promise.successful(ErrorDropAction())
        }
        val preBridgeMatch = preBridgeResult.pmatch.asInstanceOf[WildcardMatch]

        var action: Future[Coordinator.Action] = null
        Ethernet.isMcast(dstDlAddress) match {
          case true =>
            // L2 Multicast
            val nwDst = preBridgeMatch.getNetworkDestinationIPv4
            if (Ethernet.isBroadcast(dstDlAddress) &&
                    preBridgeMatch.getEtherType == ARP.ETHERTYPE &&
                    rtrIpToMac.contains(nwDst)) {
                // Forward broadcast ARPs to their routers if we know how.
                log.info("The packet is intended for an interior router port.")
                val rtrPortID = rtrMacToLogicalPortId.get(
                    rtrIpToMac.get(nwDst).get).get
                action = Promise.successful(
                    Coordinator.ToPortAction(rtrPortID))
            } else {
                // Not an ARP request for a router's port's address.
                // Flood to materialized ports only.
                log.info("flooding to port set {}", id)
                action = Promise.successful(ToPortSetAction(id))
            }
          case false =>
            // L2 unicast
            log.info("The packet has a unicast L2 dst address.")
            rtrMacToLogicalPortId.get(dstDlAddress) match {
                case Some(logicalPort: UUID) =>
                    // dstMAC is a logical port's MAC.
                    log.info("The packet is intended for an interior router " +
                        "port.")
                    action = Promise.successful(ToPortAction(logicalPort))
                case None =>
                    // Not a logical port's MAC.  Is dst MAC in
                    // macPortMap? (ie, learned)
                    log.info("Have we learned this mac address already?")
                    val port = getPortOfMac(dstDlAddress, expiry, ec)

                    action = port map {
                        case null =>
                            // The mac has not been learned. Flood.
                            ToPortSetAction(id)
                        case portID: UUID =>
                            ToPortAction(portID)
                    }
            }
        }

        return action map doPostBridging(packetContext, preBridgeMatch)
    }

    private def doPostBridging(packetContext: PacketContext,
                               preBridgeMatch: WildcardMatch)
                              (act: Coordinator.Action): Coordinator.Action = {
        // First, learn the mac-port entry.
        // TODO(pino): what if the filters can modify the L2 addresses?
        learnMacOnPort(preBridgeMatch.getEthernetSource,
            preBridgeMatch.getInputPortUUID, packetContext)
        //XXX: Add to traversed elements list if flooding.

        // If the packet's not being forwarded, we're done.
        // Otherwise, apply egress (post-bridging) chain
        act match {
            case a: Coordinator.ToPortAction =>
                packetContext.outPortID = a.outPort
            case a: Coordinator.ToPortSetAction =>
                packetContext.outPortID = a.portSetID
            case _ =>
                return act
        }
        val postBridgeResult = Chain.apply(outFilter, packetContext,
            preBridgeMatch, id, false)
        if (postBridgeResult.action == Action.DROP ||
            postBridgeResult.action == Action.REJECT) {
            // TODO: Do something more for REJECT?
            return DropAction()
        } else if (postBridgeResult.action != Action.ACCEPT) {
            log.error("Post-bridging for {} returned an action which was " +
                "{}, not ACCEPT, DROP, or REJECT.", id,
                postBridgeResult.action)
            // TODO(pino): decrement the mac-port reference count?
            return ErrorDropAction()
        } else {
            // TODO(pino): can the filter change the output port?
            return act
        }
    }

    private def learnMacOnPort(srcDlAddress: MAC, inPort: UUID,
                               packetContext: PacketContext) {
        // Learn the src MAC unless it's a logical port's.
        if (!rtrMacToLogicalPortId.contains(srcDlAddress)) {
            flowCount.increment(srcDlAddress, inPort)
            packetContext.addFlowRemovedCallback(
                flowRemovedCallbackGen.getCallback(srcDlAddress, inPort))
            // Pass the tag to be used to index the flow
            val tag = (id, srcDlAddress, inPort)
            packetContext.addFlowTag(tag)
            // Any flow invalidations caused by MACs migrating between ports
            // are done by the BridgeManager, which detects them from the
            // flowCount.increment call.
        }
    }

    private def getPortOfMac(mac: MAC, expiry: Long, ec: ExecutionContext) = {
        val rv = Promise[UUID]()(ec)
        macPortMap.get(mac, new Callback1[UUID] {
            def call(port: UUID) {
                rv.success(port)
            }
        }, expiry)
        rv
    }
}
