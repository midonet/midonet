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
import org.slf4j.LoggerFactory

import com.midokura.midolman.rules.RuleResult.Action
import com.midokura.midolman.simulation.Coordinator._
import com.midokura.midolman.topology.{MacFlowCount,
                                       RemoveFlowCallbackGenerator}
import com.midokura.midonet.cluster.client.MacLearningTable
import com.midokura.packets.{ARP, Ethernet, IntIPv4, MAC}
import com.midokura.sdn.flows.WildcardMatch
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
        if (Ethernet.isMcast(ingressMatch.getEthernetSource))
            Promise.successful(new DropAction)(ec)
        else
            normalProcess(ingressMatch, packet, packetContext, expiry, ec)
    }

    def normalProcess(ingressMatch: WildcardMatch, packet: Ethernet,
                      packetContext: PacketContext, expiry: Long,
                      ec: ExecutionContext): Future[Coordinator.Action] = {
        val srcDlAddress = ingressMatch.getEthernetSource
        val dstDlAddress = ingressMatch.getEthernetDestination

        var outPortID: Future[UUID] = null

        // Call ingress (pre-bridging) chain
        // TODO: Verify with Pino that this is how the ingress port is
        // communicated to the FE, as it's not an argument to process().
        packetContext.inPortID = ingressMatch.getInputPortUUID
        packetContext.outPortID = null
        val preBridgeResult = Chain.apply(inFilter, packetContext, ingressMatch,
                                          id, false)
        if (preBridgeResult.action == Action.DROP ||
                preBridgeResult.action == Action.REJECT) {
            // TOOD: Do something more for REJECT?
            return Promise.successful(new DropAction)(ec)
        } else if (preBridgeResult.action != Action.ACCEPT) {
            log.error("Pre-bridging for {} returned an action which was {}, " +
                      "not ACCEPT, DROP, or REJECT.", id,
                      preBridgeResult.action)
        }
        if (!(preBridgeResult.pmatch.isInstanceOf[WildcardMatch])) {
            log.error("Pre-bridging for {} returned a match which was {}, " +
                      "not a WildcardMatch.", id, preBridgeResult.pmatch)
            return Promise.successful(new DropAction)(ec)
        }
        val preBridgeMatch = preBridgeResult.pmatch.asInstanceOf[WildcardMatch]

        Ethernet.isMcast(dstDlAddress) match {
          case true =>
            // L2 Multicast
            val nwDst = preBridgeMatch.getNetworkDestinationIPv4
            if (Ethernet.isBroadcast(dstDlAddress) &&
                    preBridgeMatch.getEtherType == ARP.ETHERTYPE &&
                    rtrIpToMac.contains(nwDst)) {
                // Forward broadcast ARPs to their routers if we know how.
                val rtrMAC: MAC = rtrIpToMac.get(nwDst).get
                outPortID = Promise.successful(
                                rtrMacToLogicalPortId.get(rtrMAC).get)(ec)
            } else {
                // Not an ARP request for a router's port's address.
                // Flood to materialized ports only.
                log.info("flooding to port set {}", id)
                // Leave the outPortID null in this case.
                outPortID = Promise.successful(null)(ec)
            }
          case false =>
            // L2 unicast
            outPortID = rtrMacToLogicalPortId.get(dstDlAddress) match {
                case Some(logicalPort: UUID) =>
                    // dstMAC is a logical port's MAC.
                    Promise.successful(logicalPort)(ec)
                case None =>
                    // Not a logical port's MAC.  Is dst MAC in
                    // macPortMap? (ie, learned)
                    val learnedPort = getPortOfMac(dstDlAddress, expiry, ec)
                    flow {
                        val port = learnedPort()
                        if (port == null) {
                            /* If neither learned nor logical, flood. */
                            // XXX(pino): The actions used for FE results
                            // distinguish single ports (else-branch) from
                            // port sets (this branch), represent that in
                            // the future somehow.
                            id
                        } else
                            port
                    }(ec)
            }
        }

        // Learn the src MAC unless it's a logical port's.
        if (!rtrMacToLogicalPortId.contains(srcDlAddress)) {
            flowCount.increment(srcDlAddress, preBridgeMatch.getInputPortUUID)
            packetContext.addFlowRemovedCallback(
                        flowRemovedCallbackGen.getCallback(srcDlAddress,
                                preBridgeMatch.getInputPortUUID))
            // Pass the tag to be used to index the flow
            val tag = (id, srcDlAddress, preBridgeMatch.getInputPortUUID)
            packetContext.addFlowTag(tag)
            // Any flow invalidations caused by MACs migrating between ports
            // are done by the BridgeManager, which detects them from the
            // flowCount.increment call.
        }

        //XXX: Add to traversed elements list if flooding.

        flow {
            // apply egress (post-bridging) chain
            packetContext.outPortID = outPortID()
            val postBridgeResult = Chain.apply(outFilter, packetContext,
                                               preBridgeMatch, id, false)
            if (postBridgeResult.action == Action.DROP ||
                    postBridgeResult.action == Action.REJECT) {
                // TOOD: Do something more for REJECT?
                new DropAction
            } else if (postBridgeResult.action != Action.ACCEPT) {
                log.error("Post-bridging for {} returned an action which was " +
                          "{}, not ACCEPT, DROP, or REJECT.", id,
                          postBridgeResult.action)
                new DropAction
            } else {
                val postBridgeMatch = 
                        postBridgeResult.pmatch.asInstanceOf[WildcardMatch]

                packetContext.outPortID match {
                    case null => ToPortSetAction(id, postBridgeMatch)
                    case portID: UUID => ToPortAction(portID, postBridgeMatch)
                }
            }
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
