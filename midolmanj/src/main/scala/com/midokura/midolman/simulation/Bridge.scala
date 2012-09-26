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

    override def process(packetContext: PacketContext)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem)
            : Future[Coordinator.Action] = {
        log.info("Bridge's process method called.")
        // Drop the packet if its L2 source is a multicast address.
        if (Ethernet.isMcast(packetContext.getMatch.getEthernetSource)) {
            log.info("Bridge dropping a packet with a multi/broadcast source")
            Promise.successful(DropAction())
        } else
            normalProcess(packetContext)
    }

    def normalProcess(packetContext: PacketContext)
                     (implicit ec: ExecutionContext)
    : Future[Coordinator.Action] = {
        val srcDlAddress = packetContext.getMatch.getEthernetSource
        val dstDlAddress = packetContext.getMatch.getEthernetDestination

        // Call ingress (pre-bridging) chain
        // InputPort is already set.
        packetContext.setOutputPort(null)
        val preBridgeResult = Chain.apply(inFilter, packetContext,
                                          packetContext.getMatch, id, false)
        log.info("The ingress chain returned {}", preBridgeResult)

        if (preBridgeResult.action == Action.DROP ||
                preBridgeResult.action == Action.REJECT) {
            // TOOD: Do something more for REJECT?
            learnMacOnPort(srcDlAddress, packetContext)
            return Promise.successful(DropAction())
        } else if (preBridgeResult.action != Action.ACCEPT) {
            log.error("Pre-bridging for {} returned an action which was {}, " +
                      "not ACCEPT, DROP, or REJECT.", id,
                      preBridgeResult.action)
            return Promise.successful(ErrorDropAction())
        }
        if (preBridgeResult.pmatch ne packetContext.getMatch) {
            log.error("Pre-bridging for {} returned a different match object",
                      id)
            return Promise.successful(ErrorDropAction())
        }

        var action: Future[Coordinator.Action] = null
        Ethernet.isMcast(dstDlAddress) match {
          case true =>
            // L2 Multicast
            val nwDst = packetContext.getMatch.getNetworkDestinationIPv4
            if (Ethernet.isBroadcast(dstDlAddress) &&
                    packetContext.getMatch.getEtherType == ARP.ETHERTYPE &&
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
                    val port = getPortOfMac(dstDlAddress, packetContext.expiry,
                                            ec)

                    action = port map {
                        case null =>
                            // The mac has not been learned. Flood.
                            log.debug("Dst MAC {} is not learned. Flood",
                                dstDlAddress)
                            ToPortSetAction(id)
                        case portID: UUID =>
                            log.debug("Dst MAC {} is on port {}. Forward.",
                                dstDlAddress, portID)
                            ToPortAction(portID)
                    }
            }
        }

        return action map doPostBridging(packetContext)
    }

    private def doPostBridging(packetContext: PacketContext)
                              (act: Coordinator.Action): Coordinator.Action = {
        // First, learn the mac-port entry.
        // TODO(pino): what if the filters can modify the L2 addresses?
        learnMacOnPort(packetContext.getMatch.getEthernetSource, packetContext)
        //XXX: Add to traversed elements list if flooding.

        // If the packet's not being forwarded, we're done.
        // Otherwise, apply egress (post-bridging) chain
        act match {
            case a: Coordinator.ToPortAction =>
                packetContext.setOutputPort(a.outPort)
            case a: Coordinator.ToPortSetAction =>
                packetContext.setOutputPort(a.portSetID)
            case _ =>
                log.debug("Dropping the packet after mac-learning.")
                return act
        }
        val postBridgeResult = Chain.apply(outFilter, packetContext,
                                           packetContext.getMatch, id, false)
        if (postBridgeResult.action == Action.DROP ||
                postBridgeResult.action == Action.REJECT) {
            log.debug("Dropping the packet due to egress filter.")
            // TODO: Do something more for REJECT?
            return DropAction()
        } else if (postBridgeResult.action != Action.ACCEPT) {
            log.error("Post-bridging for {} returned an action which was {}, " +
                      "not ACCEPT, DROP, or REJECT.", id,
                      postBridgeResult.action)
            // TODO(pino): decrement the mac-port reference count?
            return ErrorDropAction()
        } else {
            log.debug("Forwarding the packet with action {}", act)
            // Note that the filter is not permitted to change the output port.
            return act
        }
    }

    private def learnMacOnPort(srcDlAddress: MAC,
                               packetContext: PacketContext) {
        // Learn the src MAC unless it's a logical port's.
        if (!rtrMacToLogicalPortId.contains(srcDlAddress)) {
            log.debug("Increasing the reference count for MAC {} on port {}",
                srcDlAddress, packetContext.getInPortId())
            flowCount.increment(srcDlAddress, packetContext.getInPortId)
            packetContext.addFlowRemovedCallback(
                flowRemovedCallbackGen.getCallback(srcDlAddress,
                                                   packetContext.getInPortId))
            // Pass the tag to be used to index the flow
            val tag = (id, srcDlAddress, packetContext.getInPortId)
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
