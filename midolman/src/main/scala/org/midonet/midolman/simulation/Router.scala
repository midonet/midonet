/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.simulation

import java.util.UUID
import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem

import org.midonet.cluster.client.Port
import org.midonet.midolman.{PacketsEntryPoint, Ready, Urgent}
import org.midonet.cluster.client.RouterPort
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.Coordinator._
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology._
import org.midonet.odp.Packet
import org.midonet.packets._
import org.midonet.sdn.flows.WildcardMatch

/**
 * The IPv4 specific implementation of a Router.
 */
class Router(override val id: UUID, override val cfg: RouterConfig,
             override val rTable: RoutingTableWrapper[IPv4Addr],
             override val inFilter: Chain, override val outFilter: Chain,
             override val loadBalancer: LoadBalancer,
             override val routerMgrTagger: TagManager,
             val arpTable: ArpTable)
            (implicit system: ActorSystem)
        extends RouterBase[IPv4Addr] {

    override val validEthertypes: Set[Short] = Set(IPv4.ETHERTYPE, ARP.ETHERTYPE)

    override def unsupportedPacketAction = NotIPv4Action

    private def processArp(pkt: IPacket, inPort: RouterPort)
                          (implicit ec: ExecutionContext,
                                    originalPktContext: PacketContext): Action =
        pkt match {
            case arp: ARP => arp.getOpCode match {
                case ARP.OP_REQUEST =>
                    processArpRequest(arp, inPort)
                    ConsumedAction
                case ARP.OP_REPLY =>
                    processArpReply(arp, inPort)
                    ConsumedAction
                case _ =>
                    DropAction
                }
            case _ =>
                log.warning("Non-ARP packet with ethertype ARP: {}", pkt)(null)
                DropAction
        }

    override protected def handleL2Broadcast(inPort: RouterPort)
                                            (implicit ec: ExecutionContext,
                                                      pktContext: PacketContext)
    : Action = {

        // Broadcast packet:  Handle if ARP, drop otherwise.
        val payload = pktContext.ethernet.getPayload
        if (pktContext.wcmatch.getEtherType == ARP.ETHERTYPE)
            processArp(payload, inPort)
        else
            DropAction
    }

    override def handleNeighbouring(inPort: RouterPort)
                                   (implicit ec: ExecutionContext,
                                             pktContext: PacketContext)
    : Option[Action] = {
        if (pktContext.wcmatch.getEtherType == ARP.ETHERTYPE) {
            // Non-broadcast ARP.  Handle reply, drop rest.
            val payload = pktContext.ethernet.getPayload
            Some(processArp(payload, inPort))
        } else
            None
    }

    private def processArpRequest(pkt: ARP, inPort: RouterPort)
                                 (implicit ec: ExecutionContext,
                                           origPktContext: PacketContext) {
        if (pkt.getProtocolType != ARP.PROTO_TYPE_IP)
            return

        val tpa = IPv4Addr.fromBytes(pkt.getTargetProtocolAddress)
        val spa = IPv4Addr.fromBytes(pkt.getSenderProtocolAddress)
        val tha = pkt.getTargetHardwareAddress
        val sha = pkt.getSenderHardwareAddress

        if (!inPort.portAddr.containsAddress(spa)) {
            log.debug("Ignoring ARP request from address {} not in the " +
                "ingress port network {}", spa, inPort.portAddr)
            return
        }

        // gratuitous arp request
        if (tpa == spa && tha == MAC.fromString("00:00:00:00:00:00")) {
            log.debug("Received a gratuitous ARP request from {}", spa)
            // TODO(pino, gontanon): check whether the refresh is needed?
            arpTable.set(spa, sha)
            return
        }
        if (!inPort.portAddr.getAddress.equals(tpa)) {
            log.debug("Ignoring ARP Request to dst ip {} instead of " +
                "inPort's {}", tpa, inPort.portAddr)
            return
        }

        // Attempt to refresh the router's arp table.
        arpTable.setAndGet(spa,
                           pkt.getSenderHardwareAddress,
                           inPort,
                           origPktContext.expiry) onSuccess { case _ =>
            log.debug("replying to ARP request from {} for {} with own mac {}",
                Array[Object](spa, tpa, inPort.portMac))

            // Construct the reply, reversing src/dst fields from the request.
            val eth = ARP.makeArpReply(inPort.portMac, sha,
                pkt.getTargetProtocolAddress, pkt.getSenderProtocolAddress)
            PacketsEntryPoint ! EmitGeneratedPacket(
                inPort.id, eth,
                if (origPktContext != null) origPktContext.flowCookie else None)
        }
    }

    private def processArpReply(pkt: ARP, port: RouterPort)
                               (implicit ec: ExecutionContext,
                                         pktContext: PacketContext) {

        // Verify the reply:  It's addressed to our MAC & IP, and is about
        // the MAC for an IPv4 address.
        if (pkt.getHardwareType != ARP.HW_TYPE_ETHERNET ||
                pkt.getProtocolType != ARP.PROTO_TYPE_IP) {
            log.debug("Router {} ignoring ARP reply on port {} because hwtype "+
                      "wasn't Ethernet or prototype wasn't IPv4.", id, port.id)
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
            log.debug("Router {} got a gratuitous ARP reply from {}", id, spa)
        } else if (!isAddressedToThis) {
            // The ARP is not gratuitous, so it should be intended for us.
            log.debug("Router {} ignoring ARP reply on port {} because tpa or "+
                      "tha doesn't match.", id, port.id)
            return
        } else {
            log.debug("Router {} received an ARP reply from {}", id, spa)
        }

        // Question:  Should we check if the ARP reply disagrees with an
        // existing cache entry and make noise if so?
        if (!port.portAddr.containsAddress(spa)) {
            log.debug("Ignoring ARP reply from address {} not in the ingress " +
                      "port network {}", spa, port.portAddr)
            return
        }

        arpTable.set(spa, sha)
    }

    override protected def isIcmpEchoRequest(mmatch: WildcardMatch): Boolean = {
        mmatch.getNetworkProtocol == ICMP.PROTOCOL_NUMBER &&
            (mmatch.getTransportSource & 0xff) == ICMP.TYPE_ECHO_REQUEST &&
            (mmatch.getTransportDestination & 0xff) == ICMP.CODE_NONE
    }

    override protected def sendIcmpEchoReply(ingressMatch: WildcardMatch,
                                             packet: Ethernet, expiry: Long)
                                   (implicit ec: ExecutionContext,
                                             packetContext: PacketContext)
    : Urgent[Boolean] = {

        val echo = packet.getPayload match {
            case ip: IPv4 => ip.getPayload match {
                                case icmp: ICMP => icmp
                                case _ => null
                             }
            case _ => null
        }

        if (echo == null)
            return Ready(true)

        val reply = new ICMP()
        reply.setEchoReply(echo.getIdentifier, echo.getSequenceNum, echo.getData)
        val ip = new IPv4()
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)
        ip.setDestinationAddress(ingressMatch.getNetworkSourceIP.asInstanceOf[IPv4Addr])
        ip.setSourceAddress(ingressMatch.getNetworkDestinationIP.asInstanceOf[IPv4Addr])
        ip.setPayload(reply)

        sendIPPacket(ip, expiry)
    }

    private def getPeerMac(rtrPort: RouterPort, expiry: Long)
                          (implicit ec: ExecutionContext,
                                    pktContext: PacketContext)
    : Urgent[MAC] = {
        expiringAsk[Port](rtrPort.peerID, log, expiry) map {
            case rp: RouterPort => rp.portMac
            case _ => null
        }
    }

    private def getMacForIP(port: RouterPort, nextHopIP: IPv4Addr,
                            expiry: Long)
                           (implicit ec: ExecutionContext,
                                     pktContext: PacketContext)
    : Urgent[MAC] = {

        if (port.isInterior) {
            return arpTable.get(nextHopIP, port, expiry)
        }

        port.nwSubnet match {
            case extAddr: IPv4Subnet if extAddr.containsAddress(nextHopIP) =>
                arpTable.get(nextHopIP, port, expiry)
            case extAddr: IPv4Subnet =>
                log.warning("getMacForIP: cannot get MAC for {} - address not" +
                            "in network segment of port {} ({})",
                            nextHopIP, port.id, extAddr)
                Ready(null)
            case _ =>
                throw new IllegalArgumentException("Arping for non-IPv4 addr")
        }
    }

    override protected def getNextHopMac(outPort: RouterPort, rt: Route,
                                         ipDest: IPv4Addr, expiry: Long)
                                        (implicit ec: ExecutionContext,
                                                  pktContext: PacketContext)
    : Urgent[MAC] = {
        if (outPort == null)
            return Ready(null)

        if (outPort.isInterior && outPort.peerID == null) {
            log.warning("Packet sent to dangling interior port {}",
                        rt.nextHopPort)
            return Ready(null)
        }

        (outPort match {
            case p: Port if p.isInterior => getPeerMac(p, expiry)
            case _ => Ready(null) // Fall through to ARP'ing below.
        }) flatMap {
            case null =>
                val nextHopInt = rt.nextHopGateway
                val nextHopIP =
                    if (nextHopInt == 0 || nextHopInt == -1) ipDest // last hop
                    else IPv4Addr(nextHopInt)
                getMacForIP(outPort, nextHopIP, expiry)
            case mac => Ready(mac)
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
     *      + no wildcard match cloning or updating.
     *      + it does not return an action but, instead sends it emits the
     *        packet for simulation if successful.
     */
    def sendIPPacket(packet: IPv4, expiry: Long)
                    (implicit ec: ExecutionContext,
                              packetContext: PacketContext): Urgent[Boolean] = {

        /**
         * Applies some post-chain transformations that might be necessary on
         * a generated packet, for example SNAT when replying to an ICMP ECHO
         * to a DNAT'd address in one of the router's port. See #547 for
         * further motivation.
         */
        def _applyPostActions(eth: Ethernet, postRoutingResult: RuleResult) = {
            val tpSrc = postRoutingResult.pmatch.getTransportSource
            packet.getProtocol match {
                case UDP.PROTOCOL_NUMBER =>
                    val tp = packet.getPayload.asInstanceOf[UDP]
                    tp.setSourcePort(tpSrc)
                    packet.setPayload(tp)
                case TCP.PROTOCOL_NUMBER =>
                    val tp = packet.getPayload.asInstanceOf[TCP]
                    tp.setSourcePort(tpSrc)
                    packet.setPayload(tp)
                case _ =>
            }

            packet.setSourceAddress(postRoutingResult.pmatch
                                    .getNetworkSourceIP.asInstanceOf[IPv4Addr])
            eth.setPayload(packet)
        }

        def _sendIPPacket(outPort: RouterPort, rt: Route): Urgent[Boolean] = {
            if (packet.getDestinationIPAddress == outPort.portAddr.getAddress) {
                /* should never happen: it means we are trying to send a packet
                 * to ourselves, probably means that somebody sent an IP packet
                 * with a forged source address belonging to this router.
                 */
                log.error("Router {} trying to send a packet {} to itself.",
                          id, packet)
                return Ready(false)
            }

            getNextHopMac(
                outPort, rt, packet.getDestinationIPAddress, expiry
            ) map {
                case null =>
                    log.error("Failed to get MAC to emit local packet")
                    false
                case mac =>
                    val eth = new Ethernet().setEtherType(IPv4.ETHERTYPE)
                    eth.setPayload(packet)
                    eth.setSourceMACAddress(outPort.portMac)
                    eth.setDestinationMACAddress(mac)
                    // Apply post-routing (egress) chain.
                    val egrMatch = WildcardMatch.fromEthernetPacket(eth)
                    val egrPktContext = new PacketContext(
                        Right(outPort.id), Packet.fromEthernet(eth), 0, null,
                        null, null, None, egrMatch)
                    egrPktContext.outPortId = outPort.id
                    val postRoutingResult = Chain.apply(outFilter,
                                       egrPktContext, egrMatch, id, false)
                    _applyPostActions(eth, postRoutingResult)

                    postRoutingResult.action match {
                        case RuleResult.Action.ACCEPT =>
                            val cookie = if (packetContext == null) None
                                else packetContext.flowCookie
                            PacketsEntryPoint !
                                EmitGeneratedPacket(rt.nextHopPort, eth, cookie)
                        case RuleResult.Action.DROP =>
                        case RuleResult.Action.REJECT =>
                        case other =>
                            log.error("Post-routing for {} returned {}, not " +
                                      "ACCEPT, DROP or REJECT.", id, other)
                    }
                true
            }
        }

        val ipMatch = new WildcardMatch()
                      .setNetworkDestination(packet.getDestinationIPAddress)
                      .setNetworkSource(packet.getSourceIPAddress)
        val rt: Route = routeBalancer.lookup(ipMatch)
        if (rt == null || rt.nextHop != Route.NextHop.PORT)
            return Ready(false)
        if (rt.nextHopPort == null)
            return Ready(false)

        getRouterPort(rt.nextHopPort, expiry) flatMap {
            p => _sendIPPacket(p, rt)
        }
    }
}
