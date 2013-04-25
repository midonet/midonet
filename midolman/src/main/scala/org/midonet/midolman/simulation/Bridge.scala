/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.simulation

import akka.actor.ActorSystem
import akka.dispatch.{ExecutionContext, Future, Promise}
import scala.collection.{Map => ROMap}
import java.util.UUID

import org.midonet.cluster.client.{IpMacMap, MacLearningTable}
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.simulation.Coordinator._
import org.midonet.midolman.topology.{FlowTagger, MacFlowCount,
                                      RemoveFlowCallbackGenerator}
import org.midonet.packets._
import org.midonet.util.functors.Callback1
import org.midonet.midolman.DeduplicationActor
import org.midonet.midolman.simulation.Coordinator.ConsumedAction
import org.midonet.midolman.simulation.Coordinator.ToPortSetAction
import org.midonet.midolman.simulation.Coordinator.DropAction
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.simulation.Coordinator.ToPortAction
import org.midonet.midolman.simulation.Coordinator.ErrorDropAction


class Bridge(val id: UUID, val tunnelKey: Long,
             val macPortMap: MacLearningTable,
             val ip4MacMap: IpMacMap[IPv4Addr],
             val flowCount: MacFlowCount, val inFilter: Chain,
             val outFilter: Chain,
             val flowRemovedCallbackGen: RemoveFlowCallbackGenerator,
             val rtrMacToLogicalPortId: ROMap[MAC, UUID],
             val rtrIpToMac: ROMap[IPAddr, MAC])
            (implicit val actorSystem: ActorSystem) extends Device {

    var log = LoggerFactory.getSimulationAwareLog(this.getClass)(actorSystem.eventStream)

    override def process(packetContext: PacketContext)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem)
            : Future[Coordinator.Action] = {
        implicit val pktContext = packetContext
        // Drop the packet if its L2 source is a multicast address.
        log.debug("Bridge's process method called.")
        if (Ethernet.isMcast(packetContext.getMatch.getEthernetSource)) {
            log.info("Bridge dropping a packet with a multi/broadcast source")
            Promise.successful(DropAction())
        } else
            normalProcess(packetContext)
    }

    def normalProcess(packetContext: PacketContext)
                     (implicit ec: ExecutionContext)
    : Future[Coordinator.Action] = {
        implicit val pktContext = packetContext
        val srcDlAddress = packetContext.getMatch.getEthernetSource
        val dstDlAddress = packetContext.getMatch.getEthernetDestination

        // Tag the flow with this Bridge ID
        packetContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(id))

        // Call ingress (pre-bridging) chain
        // InputPort is already set.
        packetContext.setOutputPort(null)
        val preBridgeResult = Chain.apply(inFilter, packetContext,
                                          packetContext.getMatch, id, false)
        log.debug("The ingress chain returned {}", preBridgeResult)

        if (preBridgeResult.action == Action.DROP ||
                preBridgeResult.action == Action.REJECT) {
            updateFlowCount(srcDlAddress, packetContext)
            // No point in tagging by dst-MAC+Port because the outPort was
            // not used in deciding to drop the flow.
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
            val nwDst = packetContext.getMatch.getNetworkDestinationIP
            if (Ethernet.isBroadcast(dstDlAddress) &&
                packetContext.getMatch.getEtherType == ARP.ETHERTYPE) {
                if (rtrIpToMac.contains(nwDst)) {
                    // Forward broadcast ARPs to their routers if we know how.
                    log.debug("The packet is intended for an interior router port.")
                    val rtrPortID = rtrMacToLogicalPortId.get(
                        rtrIpToMac.get(nwDst).get).get
                    action = Promise.successful(
                        Coordinator.ToPortAction(rtrPortID))
                } else {
                    // If it's an ARP request, can we answer it from the
                    // Bridge's IpMacMap?
                    var mac: Future[MAC]= null
                    packetContext.getMatch.getNetworkProtocol match {
                        case ARP.OP_REQUEST =>
                            mac = getMacOfIp(packetContext.getMatch
                                .getNetworkDestinationIP.asInstanceOf[IPv4Addr],
                                packetContext.expiry, ec)
                        case _ => Promise.successful[MAC](null)
                    }
                    // TODO(pino): tag the flow with the destination
                    // TODO: mac, so we can deal with changes in the mac.
                    action = mac map {
                        case null =>
                            // We don't know the mac for this IP4 or it's an
                            // ARP reply. Either way, broadcast it.
                            log.debug("flooding ARP to port set {}, source MAC {}", id,
                                packetContext.getMatch.getEthernetSource)
                            packetContext.addFlowTag(
                                FlowTagger.invalidateArpRequests(id))
                            ToPortSetAction(id)
                        case m: MAC =>
                            // We can reply to the ARP request.
                            processArpRequest(
                                pktContext.getFrame.getPayload.asInstanceOf[ARP],
                                m, pktContext.getInPortId)
                            new ConsumedAction
                    }
                }
            } else {
                // Not an ARP request.
                // Flood to materialized ports only.
                log.debug("flooding to port set {}", id)
                action = Promise.successful(ToPortSetAction(id))
                packetContext.addFlowTag(
                    FlowTagger.invalidateBroadcastFlows(id, id))
            }
          case false =>
            // L2 unicast
            rtrMacToLogicalPortId.get(dstDlAddress) match {
                case Some(logicalPort: UUID) =>
                    // dstMAC is a logical port's MAC.
                    log.debug("The packet is intended for an interior router " +
                        "port.")
                    action = Promise.successful(ToPortAction(logicalPort))
                    packetContext.addFlowTag(
                        FlowTagger.invalidateFlowsByLogicalPort(id, logicalPort))
                case None =>
                    // Not a logical port's MAC. Is dst MAC in
                    // macPortMap? (ie, learned)
                    val port = getPortOfMac(dstDlAddress, packetContext.expiry,
                            ec)

                    // Tag the flow with the (dst-port, dst-mac) pair so we can
                    // invalidate the flow if the MAC migrates.
                    action = port map {
                        case null =>
                            // The mac has not been learned. Flood.
                            log.debug("Dst MAC {} is not learned. Flood",
                                dstDlAddress)
                            packetContext.addFlowTag(
                                FlowTagger.invalidateFloodedFlowsByDstMac(
                                    id, dstDlAddress))
                            ToPortSetAction(id)
                        case portID: UUID =>
                            log.debug("Dst MAC {} is on port {}. Forward.",
                                dstDlAddress, portID)
                            packetContext.addFlowTag(
                                FlowTagger.invalidateFlowsByPort(
                                    id, dstDlAddress, portID))
                            ToPortAction(portID)
                    }
            }
        }

        action map doPostBridging(packetContext)
    }

    private def doPostBridging(packetContext: PacketContext)
                              (act: Coordinator.Action): Coordinator.Action = {
        implicit val pktContext = packetContext
        // First, learn the mac-port entry.
        // TODO(pino): what if the filters can modify the L2 addresses?
        updateFlowCount(packetContext.getMatch.getEthernetSource, packetContext)
        //XXX: Add to traversed elements list if flooding.

        // If the packet's not being forwarded, we're done.
        if (!act.isInstanceOf[Coordinator.ForwardAction]) {
            log.debug("Dropping the packet after mac-learning.")
            return act
        }

        // Otherwise, apply egress (post-bridging) chain
        act match {
            case a: Coordinator.ToPortAction =>
                packetContext.setOutputPort(a.outPort)
            case a: Coordinator.ToPortSetAction =>
                packetContext.setOutputPort(a.portSetID)
            case a =>
                log.error("Unhandled Coordinator.ForwardAction {}", a)
        }
        val postBridgeResult = Chain.apply(outFilter, packetContext,
                                           packetContext.getMatch, id, false)

        if (postBridgeResult.action == Action.DROP ||
                postBridgeResult.action == Action.REJECT) {
            log.debug("Dropping the packet due to egress filter.")
            DropAction()
        } else if (postBridgeResult.action != Action.ACCEPT) {
            log.error("Post-bridging for {} returned an action which was {}, " +
                      "not ACCEPT, DROP, or REJECT.", id,
                      postBridgeResult.action)
            // TODO(pino): decrement the mac-port reference count?
            // TODO(pino): remove the flow tag?
            ErrorDropAction()
        } else {
            log.debug("Forwarding the packet with action {}", act)
            // Note that the filter is not permitted to change the output port.
            act
        }
    }

    private def updateFlowCount(srcDlAddress: MAC,
                                packetContext: PacketContext) {
        implicit val pktContext = packetContext
        // Learn the src MAC unless it's a logical port's.
        if (!rtrMacToLogicalPortId.contains(srcDlAddress)) {
            log.debug("Increasing the reference count for MAC {} on port {}",
                srcDlAddress, packetContext.getInPortId())
            flowCount.increment(srcDlAddress, packetContext.getInPortId)
            // Add a flow-removal callback that decrements the reference count.
            packetContext.addFlowRemovedCallback(
                flowRemovedCallbackGen.getCallback(srcDlAddress,
                                                   packetContext.getInPortId))
            // Flow invalidations caused by MACs migrating between ports
            // are done by the BridgeManager's MacTableNotifyCallBack.
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

    private def getMacOfIp(ip: IPv4Addr, expiry: Long, ec: ExecutionContext) = {
        ip4MacMap match {
            case null => Promise.successful[MAC](null)
            case map =>
                val rv = Promise[MAC]()(ec)
                map.get(ip, new Callback1[MAC] {
                    def call(mac: MAC) {
                        rv.success(mac)
                    }
                }, expiry)
                rv
        }
    }

    private def processArpRequest(arpReq: ARP, mac: MAC, inPortId: UUID)
                                 (implicit ec: ExecutionContext,
                                  actorSystem: ActorSystem,
                                  originalPktContex: PacketContext) {
        // Construct the reply, reversing src/dst fields from the request.
        val eth = ARP.makeArpReply(mac, arpReq.getSenderHardwareAddress,
            arpReq.getTargetProtocolAddress, arpReq.getSenderProtocolAddress);
        DeduplicationActor.getRef(actorSystem) ! EmitGeneratedPacket(
            inPortId, eth,
            if (originalPktContex != null)
                Option(originalPktContex.getFlowCookie) else None)
    }
}
