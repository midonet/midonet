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

import java.util
import java.util.{UUID, List => JList}

import scala.concurrent.ExecutionContext

import com.typesafe.scalalogging.Logger

import org.midonet.midolman.NotYetException
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.Router.{Config, RoutingTable, TagManager}
import org.midonet.midolman.state.ArpCache
import org.midonet.midolman.topology.VirtualTopology.tryGet
import org.midonet.odp.flows.FlowKeys
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.packets._
import org.midonet.util.concurrent._
import org.midonet.util.functors.Callback0
import org.midonet.sdn.flows.FlowTagger

object Router {

    /**
     * Provides the configuration for a [[Router]].
     */
    case class Config(adminStateUp: Boolean = true,
                      inboundFilters: JList[UUID] = new util.ArrayList(0),
                      outboundFilters: JList[UUID] = new util.ArrayList(0),
                      loadBalancer: UUID = null,
                      inboundMirrors: JList[UUID] = new util.ArrayList[UUID](),
                      outboundMirrors: JList[UUID] = new util.ArrayList[UUID]()) {
        override def toString =
            s"adminStateUp=$adminStateUp inboundFilters=$inboundFilters " +
            s"outboundFilters=$outboundFilters loadBalancer=$loadBalancer"
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
             val arpCache: ArpCache)
        extends RouterBase[IPv4Addr](id, cfg, rTable, routerMgrTagger) with MirroringDevice {

    override def inboundMirrors = cfg.inboundMirrors
    override def outboundMirrors = cfg.outboundMirrors

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

        if (!inPort.portSubnet.containsAddress(spa)) {
            context.log.debug("Ignoring ARP request from address {} not in the " +
                "ingress port network {}", spa, inPort.portSubnet)
            return
        }

        // gratuitous arp request
        if (tpa == spa && tha == MAC.fromString("00:00:00:00:00:00")) {
            context.log.debug("Received a gratuitous ARP request from {}", spa)
            // TODO(pino, gontanon): check whether the refresh is needed?
            context.arpBroker.set(spa, sha, this)
            return
        }
        if (!inPort.portAddress.equals(tpa)) {
            context.log.debug("Ignoring ARP Request to dst ip {} instead of " +
                "inPort's {}", tpa, inPort.portAddress)
            return
        }

        val backChannel = context.backChannel
        val cookie = context.cookie
        val log = context.log
        // Attempt to refresh the router's arp table. Don't close over the
        // packet context, which can be reused, but instead grab only what's
        // needed.
        context.arpBroker.setAndGet(spa, pkt.getSenderHardwareAddress, inPort,
                                    this, cookie).onSuccess { case _ =>
            log.debug(s"replying to ARP request from $spa for $tpa " +
                              s"with own mac ${inPort.portMac}")

            // Construct the reply, reversing src/dst fields from the request.
            val eth = ARP.makeArpReply(inPort.portMac, sha,
                pkt.getTargetProtocolAddress, pkt.getSenderProtocolAddress)
            backChannel.tell(GeneratedLogicalPacket(inPort.id, eth, cookie))
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
        val isAddressedToThis = port.portAddress.equals(tpa) &&
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
        if (!port.portSubnet.containsAddress(spa)) {
            context.log.debug("Ignoring ARP reply from address {} not in the ingress " +
                      "port network {}", spa, port.portSubnet)
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

    private def getPeerMac(peer: Port, ipDest: IPv4Addr): MAC =
        peer match {
           case rtPort: RouterPort => rtPort.portMac
           case bridgePort: BridgePort => peekBridge(bridgePort, ipDest)
           case _ => null
        }

    private def peekBridge(port: BridgePort, ipDest: IPv4Addr): MAC = {
        // Fetch the MAC address associated with ipDest in case it
        // belongs to a router port connected to the bridge or was pre-seeded.
        val bridge = tryGet[Bridge](port.deviceId)
        bridge.ipToMac.getOrElse(ipDest, bridge.ip4MacMap.get(ipDest))
    }

    private def getMacForIP(port: RouterPort, nextHopIP: IPv4Addr,
                            context: PacketContext): MAC = {
        context.addFlowTag(FlowTagger.tagForArpEntry(id, nextHopIP))
        if (port.isInterior) {
            context.arpBroker.get(nextHopIP, port, this, context.cookie)
        } else port.portSubnet match {
            case extAddr: IPv4Subnet if extAddr.containsAddress(nextHopIP) =>
                context.arpBroker.get(nextHopIP, port, this, context.cookie)
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

        val peer: Port = if (outPort.isInterior) tryGet(outPort.peerId) else null
        var mac = getPeerMac(peer, ipDest)
        if (mac eq null) {
            val nextHopInt = rt.nextHopGateway
            val nextHopIP =
                if (nextHopInt == 0 || nextHopInt == -1) {
                    ipDest // last hop
                } else {
                    val ip = IPv4Addr(nextHopInt)
                    mac = getPeerMac(peer, ip)
                    ip
                }
            if (mac eq null)
                mac = getMacForIP(outPort, nextHopIP, context)
        }
        mac
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
        def _applyPostActions(eth: Ethernet, pktCtx: PacketContext) = {
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
            if (packet.getDestinationIPAddress == outPort.portAddress) {
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
                        new Packet(eth, egrMatch, eth.length), egrMatch, outPort.id)
                    egrPktContext.outPortId = outPort.id

                    // Try to apply the outFilter
                    applyAllFilters(egrPktContext,
                                    cfg.outboundFilters).action match {
                        case RuleResult.Action.ACCEPT =>
                            _applyPostActions(eth, egrPktContext)
                            context.addGeneratedPacket(rt.nextHopPort, eth)
                            true
                        case RuleResult.Action.DROP =>
                            false
                        case RuleResult.Action.REJECT =>
                            false
                        case other =>
                            context.log.warn("PostRouting for returned {}, not " +
                                      "ACCEPT, DROP or REJECT.", other)
                            false
                    }
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

        _sendIPPacket(tryGet[RouterPort](rt.nextHopPort), rt)
    }

    override def toString =
        s"Router [id=$id adminStateUp=${cfg.adminStateUp} " +
        s"inboundFilters=${cfg.inboundFilters} "
        s"outboundFilters=${cfg.outboundFilters} " +
        s"loadBalancer=${cfg.loadBalancer}]"
}
