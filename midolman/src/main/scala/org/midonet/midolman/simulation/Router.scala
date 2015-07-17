/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.simulation

import java.util.UUID

import org.midonet.midolman.simulation.Coordinator.CPAction
import org.midonet.sdn.flows.FlowTagger

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem

import com.typesafe.scalalogging.Logger

import org.midonet.midolman.NotYetException
import org.midonet.midolman.PacketWorkflow.{ErrorDrop, Drop, NoOp, SimulationResult}
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.simulation.Router.{Config, RoutingTable, TagManager}
import org.midonet.midolman.state.ArpCache
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology.devices.{Port, RouterPort}
import org.midonet.odp.flows.FlowKeys
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.packets._
import org.midonet.util.concurrent._
import org.midonet.util.functors.Callback0

object Router {

    /**
     * Provides the configuration for a [[Router]].
     */
    case class Config(adminStateUp: Boolean = true,
                      inboundFilter: UUID = null,
                      outboundFilter: UUID = null,
                      loadBalancer: UUID = null) {
        override def toString =
            s"adminStateUp=$adminStateUp inboundFilter=$inboundFilter " +
            s"outboundFilter=$outboundFilter loadBalancer=$loadBalancer"
    }

    /**
     * Provided to the [[Router]] for operations on tags.
     */
    trait TagManager {
        def addIPv4Tag(dstIp: IPv4Addr, matchLength: Int)
        def getFlowRemovalCallback(dstIp: IPv4Addr): Callback0
    }

    trait RoutingTable {
        def lookup(flowMatch: FlowMatch): java.util.List[Route]
        def lookup(flowMatch: FlowMatch, log: Logger): java.util.List[Route]
    }
}

/** The IPv4 specific implementation of a [[Router]]. */
class Router(override val id: UUID,
             override val cfg: Config,
             override val rTable: RoutingTable,
             override val routerMgrTagger: TagManager,
             val arpCache: ArpCache,
             override var checkpointAction: CPAction = Coordinator.NO_CHECKPOINT)
            (implicit system: ActorSystem)
        extends RouterBase[IPv4Addr](id, cfg, rTable, routerMgrTagger) {

    override def isValidEthertype(ether: Short) =
        ether == IPv4.ETHERTYPE || ether == ARP.ETHERTYPE

    private def processArp(pkt: IPacket, inPort: RouterPort)
                          (implicit context: PacketContext): SimulationResult =
        pkt match {
            case arp: ARP => arp.getOpCode match {
                case ARP.OP_REQUEST =>
                    processArpRequest(arp, inPort)
                    NoOp
                case ARP.OP_REPLY =>
                    processArpReply(arp, inPort)
                    handleBgp(context, inPort)
                case _ =>
                    Drop
                }
            case _ =>
                context.log.warn("Non-ARP packet with ethertype ARP: {}", pkt)
                Drop
        }

    override protected def handleL2Broadcast(inPort: RouterPort)
                                            (implicit context: PacketContext) = {

        // Broadcast packet:  Handle if ARP, drop otherwise.
        val payload = context.ethernet.getPayload
        if (context.wcmatch.getEtherType == ARP.ETHERTYPE)
            processArp(payload, inPort)
        else
            Drop
    }

    override def handleNeighbouring(inPort: RouterPort)
                                   (implicit context: PacketContext)
    : Option[SimulationResult] = {
        if (context.wcmatch.getEtherType == ARP.ETHERTYPE) {
            // Non-broadcast ARP.  Handle reply, drop rest.
            val payload = context.ethernet.getPayload
            Some(processArp(payload, inPort))
        } else
            None
    }

    override def applyTagsForRoute(route: Route,
                    simRes: SimulationResult)(implicit context: PacketContext): Unit = {
        simRes match {
            case ErrorDrop | NoOp =>
            case a if (route ne null) && (route.dstNetworkLength == 32) =>
            case a =>
                var matchLen = -1
                // We don't want to tag a temporary flow (e.g. created by
                // a BLACKHOLE route), and we do that to avoid excessive
                // interaction with the RouterManager, who needs to keep
                // track of every IP address the router gives to it.
                if (route ne null) {
                    context.addFlowTag(FlowTagger.tagForRoute(route))
                    matchLen = route.dstNetworkLength
                }

                val dstIp = context.wcmatch.getNetworkDstIP.asInstanceOf[IPv4Addr]
                context.addFlowTag(FlowTagger.tagForDestinationIp(id, dstIp))
                routerMgrTagger.addIPv4Tag(dstIp, matchLen)
                context.addFlowRemovedCallback(
                        routerMgrTagger.getFlowRemovalCallback(dstIp))
        }
    }


    private def processArpRequest(pkt: ARP, inPort: RouterPort)
                                 (implicit context: PacketContext) {
        if (pkt.getProtocolType != ARP.PROTO_TYPE_IP)
            return

        val tpa = IPv4Addr.fromBytes(pkt.getTargetProtocolAddress)
        val spa = IPv4Addr.fromBytes(pkt.getSenderProtocolAddress)
        val tha = pkt.getTargetHardwareAddress
        val sha = pkt.getSenderHardwareAddress

        if (!inPort.portAddr.containsAddress(spa)) {
            context.log.debug("Ignoring ARP request from address {} not in the " +
                "ingress port network {}", spa, inPort.portAddr)
            return
        }

        // gratuitous arp request
        if (tpa == spa && tha == MAC.fromString("00:00:00:00:00:00")) {
            context.log.debug("Received a gratuitous ARP request from {}", spa)
            // TODO(pino, gontanon): check whether the refresh is needed?
            context.arpBroker.set(spa, sha, this)
            return
        }
        if (!inPort.portAddr.getAddress.equals(tpa)) {
            context.log.debug("Ignoring ARP Request to dst ip {} instead of " +
                "inPort's {}", tpa, inPort.portAddr)
            return
        }

        val packetEmitter = context.packetEmitter
        // Attempt to refresh the router's arp table.
        context.arpBroker.setAndGet(spa, pkt.getSenderHardwareAddress, inPort, this).onSuccess { case _ =>
            context.log.debug(s"replying to ARP request from $spa for $tpa " +
                              s"with own mac ${inPort.portMac}")

            // Construct the reply, reversing src/dst fields from the request.
            val eth = ARP.makeArpReply(inPort.portMac, sha,
                pkt.getTargetProtocolAddress, pkt.getSenderProtocolAddress)
            packetEmitter.schedule(GeneratedPacket(inPort.id, eth))
        }(ExecutionContext.callingThread)
    }

    private def processArpReply(pkt: ARP, port: RouterPort)
                               (implicit context: PacketContext) {

        // Verify the reply:  It's addressed to our MAC & IP, and is about
        // the MAC for an IPv4 address.
        if (pkt.getHardwareType != ARP.HW_TYPE_ETHERNET ||
                pkt.getProtocolType != ARP.PROTO_TYPE_IP) {
            context.log.debug("ignoring ARP reply on port {} because hwtype "+
                "wasn't Ethernet or prototype wasn't IPv4.", port.id)
            return
        }

        val tpa = IPv4Addr.fromBytes(pkt.getTargetProtocolAddress)
        val tha: MAC = pkt.getTargetHardwareAddress
        val spa = IPv4Addr.fromBytes(pkt.getSenderProtocolAddress)
        val sha: MAC = pkt.getSenderHardwareAddress
        val isGratuitous = tpa == spa && tha == sha
        val isAddressedToThis = port.portAddr.getAddress.equals(tpa) &&
                                tha == port.portMac

        if (isGratuitous) {
            context.log.debug("got a gratuitous ARP reply from {}", spa)
        } else if (!isAddressedToThis) {
            // The ARP is not gratuitous, so it should be intended for us.
            context.log.debug("ignoring ARP reply on port {} because tpa or "+
                      "tha doesn't match.", port.id)
            return
        } else {
            context.log.debug("received an ARP reply from {}", id, spa)
        }

        // Question:  Should we check if the ARP reply disagrees with an
        // existing cache entry and make noise if so?
        if (!port.portAddr.containsAddress(spa)) {
            context.log.debug("Ignoring ARP reply from address {} not in the ingress " +
                      "port network {}", spa, port.portAddr)
            return
        }

        context.arpBroker.set(spa, sha, this)
    }

    override protected def isIcmpEchoRequest(mmatch: FlowMatch): Boolean = {
        mmatch.getNetworkProto == ICMP.PROTOCOL_NUMBER &&
            (mmatch.getSrcPort & 0xff) == ICMP.TYPE_ECHO_REQUEST &&
            (mmatch.getDstPort & 0xff) == ICMP.CODE_NONE
    }

    override protected def sendIcmpEchoReply(context: PacketContext): Boolean = {

        val echo = context.ethernet.getPayload match {
            case ip: IPv4 => ip.getPayload match {
                                case icmp: ICMP => icmp
                                case _ => null
                             }
            case _ => null
        }

        if (echo == null)
            return true

        val reply = new ICMP()
        reply.setEchoReply(echo.getIdentifier, echo.getSequenceNum, echo.getData)
        val ip = new IPv4()
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)
        ip.setDestinationAddress(context.wcmatch.getNetworkSrcIP.asInstanceOf[IPv4Addr])
        ip.setSourceAddress(context.wcmatch.getNetworkDstIP.asInstanceOf[IPv4Addr])
        ip.setPayload(reply)

        sendIPPacket(ip, context)
    }

    private def getPeerMac(rtrPort: RouterPort): MAC =
        tryAsk[Port](rtrPort.peerId) match {
           case rtPort: RouterPort => rtPort.portMac
           case _ => null
        }

    private def getMacForIP(port: RouterPort, nextHopIP: IPv4Addr,
                            context: PacketContext): MAC = {

        if (port.isInterior) {
            return context.arpBroker.get(nextHopIP, port, this)
        }

        port.nwSubnet match {
            case extAddr: IPv4Subnet if extAddr.containsAddress(nextHopIP) =>
                context.arpBroker.get(nextHopIP, port, this)
            case extAddr: IPv4Subnet =>
                context.log.warn("cannot get MAC for {} - address not " +
                                 "in network segment of port {} ({})",
                                 nextHopIP, port.id, extAddr)
                null
            case _ =>
                throw new IllegalArgumentException("ARP'ing for non-IPv4 addr")
        }
    }

    override protected def getNextHopMac(outPort: RouterPort, rt: Route,
                                         ipDest: IPv4Addr, context: PacketContext): MAC = {
        if (outPort == null)
            return null

        if (outPort.isInterior && outPort.peerId == null) {
            context.log.warn("Packet sent to dangling interior port {}",
                        rt.nextHopPort)
            return null
        }

        (outPort match {
            case p: Port if p.isInterior => getPeerMac(p)
            case _ => null // Fall through to ARP'ing below.
        }) match {
            case null =>
                val nextHopInt = rt.nextHopGateway
                val nextHopIP =
                    if (nextHopInt == 0 || nextHopInt == -1) ipDest // last hop
                    else IPv4Addr(nextHopInt)
                context.addFlowTag(FlowTagger.tagForArpEntry(id, nextHopIP))
                getMacForIP(outPort, nextHopIP, context)
            case mac => mac
        }
    }

    /**
     * Send a locally generated IP packet
     *
     * CAVEAT: this method may block, so it is suitable only for use in
     * the context of processing packets that result in a CONSUMED action.
     *
     * XXX (pino, guillermo): should we add the ability to queue simulation of
     * this device starting at a specific step? In this case it would be the
     * routing step.
     *
     * The logic here is roughly the same as that found in process() except:
     *      + the ingress and prerouting steps are skipped. We do:
     *          - forwarding
     *          - post routing (empty right now)
     *          - emit new packet
     *      + drop actions in process() are an empty return here (we just don't
     *        emit the packet)
     *      + no flow match cloning or updating.
     *      + it does not return an action but, instead sends it emits the
     *        packet for simulation if successful.
     */
    @throws[NotYetException]
    def sendIPPacket(packet: IPv4, context: PacketContext): Boolean = {

        /**
         * Applies some post-chain transformations that might be necessary on
         * a generated packet, for example SNAT when replying to an ICMP ECHO
         * to a DNAT'd address in one of the router's port. See #547 for
         * further motivation.
         */
        def _applyPostActions(eth: Ethernet, postRoutingResult: RuleResult,
                              pktCtx: PacketContext) = {
            val srcPort = pktCtx.wcmatch.getSrcPort
            packet.getProtocol match {
                case UDP.PROTOCOL_NUMBER =>
                    val l4 = packet.getPayload.asInstanceOf[UDP]
                    l4.setSourcePort(srcPort)
                    packet.setPayload(l4)
                case TCP.PROTOCOL_NUMBER =>
                    val l4 = packet.getPayload.asInstanceOf[TCP]
                    l4.setSourcePort(srcPort)
                    packet.setPayload(l4)
                case _ =>
            }

            packet.setSourceAddress(pktCtx.wcmatch
                                    .getNetworkSrcIP.asInstanceOf[IPv4Addr])
            eth.setPayload(packet)
        }

        def _sendIPPacket(outPort: RouterPort, rt: Route): Boolean = {
            if (packet.getDestinationIPAddress == outPort.portAddr.getAddress) {
                /* should never happen: it means we are trying to send a packet
                 * to ourselves, probably means that somebody sent an IP packet
                 * with a forged source address belonging to this router.
                 */
                context.log.warn("Router {} trying to send a packet {} to itself.",
                          id, packet)
                return false
            }

            getNextHopMac(outPort, rt, packet.getDestinationIPAddress, context) match {
                case null =>
                    context.log.warn("Failed to get MAC to emit local packet")
                    false
                case mac =>
                    val eth = new Ethernet().setEtherType(IPv4.ETHERTYPE)
                    eth.setPayload(packet)
                    eth.setSourceMACAddress(outPort.portMac)
                    eth.setDestinationMACAddress(mac)
                    // Apply post-routing (egress) chain.
                    val egrMatch = new FlowMatch(FlowKeys.fromEthernetPacket(eth))
                    val egrPktContext = new PacketContext(0,
                        new Packet(eth, egrMatch), egrMatch, outPort.id)
                    egrPktContext.outPortId = outPort.id

                    // Try to apply the outFilter
                    val outFilter = if (cfg.outboundFilter == null) null
                                    else tryAsk[Chain](cfg.outboundFilter)

                    val postRoutingResult = Chain.apply(outFilter,
                        egrPktContext, id, false, checkpointAction)

                    _applyPostActions(eth, postRoutingResult, egrPktContext)
                    postRoutingResult.action match {
                        case RuleResult.Action.ACCEPT =>
                            context.addGeneratedPacket(rt.nextHopPort, eth)
                        case RuleResult.Action.DROP =>
                        case RuleResult.Action.REJECT =>
                        case other =>
                            context.log.warn("PostRouting for returned {}, not " +
                                      "ACCEPT, DROP or REJECT.", other)
                    }
                    true
            }
        }

        val ipMatch = new FlowMatch()
                      .setNetworkDst(packet.getDestinationIPAddress)
                      .setNetworkSrc(packet.getSourceIPAddress)
        val rt: Route = routeBalancer.lookup(ipMatch, context.log)
        if (rt == null || rt.nextHop != Route.NextHop.PORT)
            return false
        if (rt.nextHopPort == null)
            return false

        _sendIPPacket(tryAsk[RouterPort](rt.nextHopPort), rt)
    }

    override def toString =
        s"Router [id=$id adminStateUp=${cfg.adminStateUp} " +
        s"inboundFilter=${cfg.inboundFilter} outboundFilter=${cfg.outboundFilter} " +
        s"loadBalancer=${cfg.loadBalancer}]"
}
