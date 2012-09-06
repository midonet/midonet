/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import akka.dispatch.{ExecutionContext, Future, Promise}
import akka.dispatch.Future.flow
import akka.actor.{ActorRef, Actor}
import java.util.UUID
import org.slf4j.LoggerFactory

import com.midokura.midolman.layer3.Route
import com.midokura.midolman.simulation.Coordinator._
import com.midokura.midolman.state.PortDirectory.{LogicalBridgePortConfig, LogicalRouterPortConfig, MaterializedRouterPortConfig, RouterPortConfig}
import com.midokura.midolman.topology.{ArpTable, RouterConfig,
                                       RoutingTableWrapper}
import com.midokura.midolman.SimulationController
import com.midokura.midolman.SimulationController.EmitGeneratedPacket
import com.midokura.packets.{ARP, Ethernet, ICMP, IntIPv4, IPv4, MAC}
import com.midokura.packets.ICMP.{EXCEEDED_CODE, UNREACH_CODE}
import com.midokura.sdn.flows.WildcardMatch
import akka.actor.ActorSystem


class Router(val id: UUID, val cfg: RouterConfig,
             val rTable: RoutingTableWrapper, val arpTable: ArpTable,
             val inFilter: Chain, val outFilter: Chain) extends Device {

    private val log = LoggerFactory.getLogger(classOf[Router])
    private val loadBalancer = new LoadBalancer(rTable)

    override def process(ingressMatch: WildcardMatch, packet: Ethernet,
                         pktContext: PacketContext, expiry: Long)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem): Future[Action] = {
        val hwDst = ingressMatch.getEthernetDestination
        val rtrPortCfg: RouterPortConfig = getRouterPortConfig(
                            ingressMatch.getInputPortUUID)
        if (rtrPortCfg == null) {
            log.error("Could not get configuration for port {}",
                      ingressMatch.getInputPortUUID)
            return Promise.successful(new DropAction)(ec)
        }
        if (ingressMatch.getEtherType != IPv4.ETHERTYPE &&
                ingressMatch.getEtherType != ARP.ETHERTYPE)
            return Promise.successful(new NotIPv4Action)(ec)

        if (Ethernet.isBroadcast(hwDst)) {
            // Broadcast packet:  Handle if ARP, drop otherwise.
            if (ingressMatch.getEtherType == ARP.ETHERTYPE &&
                    ingressMatch.getNetworkProtocol == ARP.OP_REQUEST) {
                processArpRequest(packet.getPayload.asInstanceOf[ARP],
                                  ingressMatch.getInputPortUUID, rtrPortCfg)
                return Promise.successful(new ConsumedAction)(ec)
            } else
                return Promise.successful(new DropAction)(ec)
        }

        if (hwDst != rtrPortCfg.getHwAddr) {
            // Not addressed to us, log.warn and drop.
            log.warn("{} neither broadcast nor inPort's MAC ({})", hwDst,
                     rtrPortCfg.getHwAddr)
            return Promise.successful(new DropAction)(ec)
        }

        if (ingressMatch.getEtherType == ARP.ETHERTYPE) {
            // Non-broadcast ARP.  Handle reply, drop rest.
            if (ingressMatch.getNetworkProtocol == ARP.OP_REPLY) {
                processArpReply(packet.getPayload.asInstanceOf[ARP],
                                ingressMatch.getInputPortUUID, rtrPortCfg)
                return Promise.successful(new ConsumedAction)(ec)
            } else
                return Promise.successful(new DropAction)(ec)
        }

        val nwDst = ingressMatch.getNetworkDestinationIPv4
        val inPortIP = new IntIPv4(rtrPortCfg.portAddr)
        if (nwDst == inPortIP) {
            // We're the L3 destination.  Reply to ICMP echos, drop the rest.
            if (isIcmpEchoRequest(ingressMatch)) {
                sendIcmpEchoReply(ingressMatch, packet, rtrPortCfg, expiry)
                return Promise.successful(new ConsumedAction)(ec)
            } else
                return Promise.successful(new DropAction)(ec)
        }

        // XXX: Apply the pre-routing (ingress) chain

        if (ingressMatch.getNetworkTTL != null) {
            val ttl: Byte = ingressMatch.getNetworkTTL
            if (ttl <= 1) {
                sendIcmpError(ingressMatch, packet,
                    ICMP.TYPE_TIME_EXCEEDED, EXCEEDED_CODE.EXCEEDED_TTL)
                return Promise.successful(new DropAction)(ec)
            } else {
                ingressMatch.setNetworkTTL((ttl - 1).toByte)
            }
        }

        val rt: Route = loadBalancer.lookup(ingressMatch)
        if (rt == null) {
            // No route to network
            sendIcmpError(ingressMatch, packet,
                ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_NET)
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHop == Route.NextHop.BLACKHOLE) {
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHop == Route.NextHop.REJECT) {
            sendIcmpError(ingressMatch, packet,
                ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_FILTER_PROHIB)
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHop != Route.NextHop.PORT) {
            log.error("Routing table lookup for {} returned invalid nextHop " +
                "of {}", nwDst, rt.nextHop)
            // TODO(jlm, pino): Should this be an exception?
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHopPort == null) {
            log.error("Routing table lookup for {} forwarded to port null.",
                nwDst)
            // TODO(pino): should we remove this route?
            return Promise.successful(new DropAction)(ec)
        }

        // XXX: Apply post-routing (egress) chain.

        val outPortCfg = getRouterPortConfig(rt.nextHopPort)
        if (outPortCfg == null) {
            log.error("Can't find the configuration for the egress port {}",
                rt.nextHopPort)
            return Promise.successful(new DropAction)(ec)
        }
        val outPortIP = new IntIPv4(outPortCfg.portAddr)
        if (nwDst == outPortIP) {
            if (isIcmpEchoRequest(ingressMatch)) {
                sendIcmpEchoReply(ingressMatch, packet, rtrPortCfg, expiry)
                return Promise.successful(new ConsumedAction)(ec)
            } else {
                return Promise.successful(new DropAction)(ec)
            }
        }
        var matchOut: WildcardMatch = null // ingressMatch.clone
        // Set HWSrc
        matchOut.setEthernetSource(outPortCfg.getHwAddr)
        // Set HWDst
        outPortCfg match {
            case logCfg: LogicalRouterPortConfig =>
                if (logCfg.peerId == null) {
                    log.warn("Packet forwarded to dangling logical port {}",
                        rt.nextHopPort)
                    sendIcmpError(ingressMatch, packet,
                        ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_NET)
                    return Promise.successful(new DropAction)(ec)
                }
                val peerMac = getPeerMac(logCfg)
                if (peerMac != null) {
                    matchOut.setEthernetDestination(peerMac)
                    return Promise.successful(
                        ForwardAction(rt.nextHopPort, matchOut))(ec)
                }
            // TODO(jlm,pino): Should not having the peerMac be an error?
            case _ => /* Fall through to ARP'ing below. */
        }
        var nextHopIP: Int = rt.nextHopGateway
        if (nextHopIP == 0 || nextHopIP == -1) {  /* Last hop */
            nextHopIP = matchOut.getNetworkDestination
        }

        getMacForIP(rt.nextHopPort, nextHopIP, expiry, ec) match {
            case None =>
                // Couldn't get the MAC.  getMacForIP will send any ICPM !H,
                // so here we just drop.
                return Promise.successful(new DropAction)(ec)
            case Some(nextHopMacFuture) => return flow {
                val nextHopMac = nextHopMacFuture()
                if (nextHopMac == null)
                    new DropAction: Action
                else {
                    matchOut.setEthernetDestination(nextHopMac)
                    new ForwardAction(rt.nextHopPort, matchOut): Action
                }
            }(ec)
        }
    }

    private def getRouterPortConfig(portID: UUID): RouterPortConfig = {
        /* guillermo: log the error here.
        if // not found
            log.error("Can't find the configuration for the egress port {}",
                rt.nextHopPort)
        */
        null //XXX
    }

    private def processArpRequest(pkt: ARP, portID: UUID,
                                  rtrPortCfg: RouterPortConfig) {
        //XXX
    }

    private def processArpReply(pkt: ARP, portID: UUID,
                                rtrPortCfg: RouterPortConfig) {
        // Verify the reply:  It's addressed to our MAC & IP, and is about
        // the MAC for an IPv4 address.
        if (pkt.getHardwareType != ARP.HW_TYPE_ETHERNET ||
                pkt.getProtocolType != ARP.PROTO_TYPE_IP) {
            log.debug("Router {} ignoring ARP reply on port {} because hwtype "+
                      "wasn't Ethernet or prototype wasn't IPv4.", id, portID)
            return
        }
        val tpa: Int = IPv4.toIPv4Address(pkt.getTargetProtocolAddress)
        val tha: MAC = pkt.getTargetHardwareAddress
        if (tpa != rtrPortCfg.portAddr || tha != rtrPortCfg.getHwAddr) {
            log.debug("Router {} ignoring ARP reply on port {} because tpa or "+
                      "tha doesn't match.", id, portID)
            return
        }
        // Question:  Should we check if the ARP reply disagrees with an
        // existing cache entry and make noise if so?

        val sha: MAC = pkt.getSenderHardwareAddress
        val spa = new IntIPv4(pkt.getSenderProtocolAddress)
        arpTable.set(spa, sha)
    }

    private def isIcmpEchoRequest(mmatch: WildcardMatch): Boolean = {
        mmatch.getNetworkProtocol == ICMP.PROTOCOL_NUMBER &&
            (mmatch.getTransportSource & 0xff) == ICMP.TYPE_ECHO_REQUEST &&
            (mmatch.getTransportDestination & 0xff) == ICMP.CODE_NONE
    }

    private def sendIcmpEchoReply(ingressMatch: WildcardMatch, packet: Ethernet,
                    rtrPortCfg: RouterPortConfig, expiry: Long)
                    (implicit ec: ExecutionContext, actorSystem: ActorSystem) {
        val echo = packet.getPayload match {
            case ip: IPv4 =>
                ip.getPayload match {
                    case icmp: ICMP => icmp
                    case _ => null
                }
            case _ => null
        }
        if (echo == null)
            return

        val reply = new ICMP()
        reply.setEchoReply(echo.getIdentifier, echo.getSequenceNum, echo.getData)
        val ip = new IPv4()
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)
        ip.setDestinationAddress(ingressMatch.getNetworkSource)
        ip.setSourceAddress(ingressMatch.getNetworkDestination)
        ip.setPayload(reply)

        sendIPPacket(ip, expiry)
    }

    private def getPeerMac(rtrPortCfg: LogicalRouterPortConfig): MAC = {
        null //XXX
    }

    private def getMacForIP(portID: UUID, nextHopIP: Int, expiry: Long,
                            ec: ExecutionContext): Option[Future[MAC]] = {
        val nwAddr = new IntIPv4(nextHopIP)
        val rtrPortConfig = getRouterPortConfig(portID)
        if (rtrPortConfig == null) {
            log.error("cannot get configuration for port {}", portID)
            return None
        }
        rtrPortConfig match {
            case mPortConfig: MaterializedRouterPortConfig =>
                val shift = 32 - mPortConfig.localNwLength
                // Shifts by 32 in java are no-ops (see
                // http://www.janeg.ca/scjp/oper/shift.html), so special case
                // nwLength=0 <=> shift=32 to always match.
                if ((nextHopIP >>> shift) !=
                        (mPortConfig.localNwAddr >>> shift) &&
                        shift != 32) {
                    log.warn("getMacForIP: cannot get MAC for {} - address " +
                        "not in network segment of port {} ({}/{})",
                        Array[Object](nwAddr, portID,
                            mPortConfig.localNwAddr.toString,
                            mPortConfig.localNwLength.toString))
                    return None
                }
            case _ => /* Fall through */
        }
        return Some(arpTable.get(nwAddr, expiry, ec))
    }

    /**
     * Given a route and a destination address, return the MAC address of
     * the next hop (or the destination's if it's a link-local destination)
     *
     * The process() method below could use this with some tweaks to the return
     * value so it can decide what action to take.
     *
     * @param rt Route that the packet will be sent through
     * @param ipv4Dest Final destination of the packet to be sent
     * @param expiry
     * @param ec
     * @return
     */
    private def getNextHopMAC(rt: Route, ipv4Dest: Int, expiry: Long)
                 (implicit ec: ExecutionContext): Option[Future[MAC]] = {
        val outPortCfg = getRouterPortConfig(rt.nextHopPort)
        if (outPortCfg == null)
            return None

        outPortCfg match {
            case logCfg: LogicalRouterPortConfig =>
                if (logCfg.peerId == null) {
                    log.warn("Packet sent to dangling logical port {}",
                        rt.nextHopPort)
                    return None
                }
                val peerMac = getPeerMac(logCfg)
                if (peerMac != null) {
                    return Some(Promise.successful(peerMac)(ec))
                }
            case _ => /* Fall through to ARP'ing below. */
        }

        var nextHopIP: Int = rt.nextHopGateway
        if (nextHopIP == 0 || nextHopIP == -1) {  /* Last hop */
            nextHopIP = ipv4Dest
        }

        getMacForIP(rt.nextHopPort, nextHopIP, expiry, ec)
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
                    (implicit ec: ExecutionContext, actorSystem: ActorSystem) {
        // XXX: use a temporary match, we could also add a lookup(src,dst)
        // flavor of lookup()
        val ipMatch = (new WildcardMatch()).
                setNetworkDestination(packet.getDestinationAddress).
                setNetworkSource(packet.getSourceAddress)

        val rt: Route = loadBalancer.lookup(ipMatch)
        if (rt == null || rt.nextHop != Route.NextHop.PORT)
            return
        if (rt.nextHopPort == null)
            return

        // XXX: Apply post-routing (egress) chain.

        val outPortCfg = getRouterPortConfig(rt.nextHopPort)
        if (outPortCfg == null)
            return
        val outPortIP = new IntIPv4(outPortCfg.portAddr)
        if (packet.getDestinationAddress == outPortIP.addressAsInt) {
            /* should never happen: it means we are trying to send a packet to
             * ourselves, probably means that somebody sent an ip packet with
             * source and dest addresses belonging to this router.
             */
            return
        }

        val eth = (new Ethernet()).setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(packet)
        eth.setDestinationMACAddress(outPortCfg.getHwAddr)

        getNextHopMAC(rt, ipMatch.getNetworkDestination, expiry) match {
            case Some(macFuture) if macFuture != null =>
                macFuture map { mac: MAC =>
                    if (mac != null) {
                        eth.setDestinationMACAddress(mac)
                        SimulationController.getRef(actorSystem).tell(
                            EmitGeneratedPacket(rt.nextHopPort, eth))
                    } else {
                        log.error(
                            "Failed to fetch MAC address to emit local packet")
                    }
                }
            case _ =>
                log.error("Failed to fetch MAC address to emit local packet")
        }
    }

    /**
     * Determine whether a packet can trigger an ICMP error.  Per RFC 1812 sec.
     * 4.3.2.7, some packets should not trigger ICMP errors:
     *   1) Other ICMP errors.
     *   2) Invalid IP packets.
     *   3) Destined to IP bcast or mcast address.
     *   4) Destined to a link-layer bcast or mcast.
     *   5) With source network prefix zero or invalid source.
     *   6) Second and later IP fragments.
     *
     * @param ethPkt
     *            We wish to know whether this packet may trigger an ICMP error
     *            message.
     * @param egressPortId
     *            If known, this is the port that would have emitted the packet.
     *            It's used to determine whether the packet was addressed to an
     *            IP (local subnet) broadcast address.
     * @return True if-and-only-if the packet meets none of the above conditions
     *         - i.e. it can trigger an ICMP error message.
     */
     def canSendIcmp(ethPkt: Ethernet, egressPortId: UUID) : Boolean = {
        var ipPkt: IPv4 = null
        ethPkt.getPayload match {
            case ip: IPv4 => ipPkt = ip
            case _ => return false
        }

        // Ignore ICMP errors.
        if (ipPkt.getProtocol() == ICMP.PROTOCOL_NUMBER) {
            ipPkt.getPayload match {
                case icmp: ICMP if icmp.isError =>
                    log.debug("Skipping generation of ICMP error for " +
                            "ICMP error packet")
                    return false
                case _ =>
            }
        }
        // TODO(pino): check the IP packet's validity - RFC1812 sec. 5.2.2
        // Ignore packets to IP mcast addresses.
        if (ipPkt.isMcast()) {
            log.debug("Not generating ICMP Unreachable for packet to an IP "
                    + "multicast address.")
            return false
        }
        // Ignore packets sent to the local-subnet IP broadcast address of the
        // intended egress port.
        if (null != egressPortId) {
            val portConfig: RouterPortConfig = getRouterPortConfig(egressPortId)
            if (null == portConfig) {
                log.error("Failed to get the egress port's config from ZK {}",
                        egressPortId)
                return false
            }
            if (ipPkt.isSubnetBcast(portConfig.nwAddr, portConfig.nwLength)) {
                log.debug("Not generating ICMP Unreachable for packet to "
                        + "the subnet local broadcast address.")
                return false
            }
        }
        // Ignore packets to Ethernet broadcast and multicast addresses.
        if (ethPkt.isMcast()) {
            log.debug("Not generating ICMP Unreachable for packet to "
                    + "Ethernet broadcast or multicast address.")
            return false
        }
        // Ignore packets with source network prefix zero or invalid source.
        // TODO(pino): See RFC 1812 sec. 5.3.7
        if (ipPkt.getSourceAddress() == 0xffffffff
                || ipPkt.getDestinationAddress() == 0xffffffff) {
            log.debug("Not generating ICMP Unreachable for all-hosts broadcast "
                    + "packet")
            return false
        }
        // TODO(pino): check this fragment offset
        // Ignore datagram fragments other than the first one.
        if (0 != (ipPkt.getFragmentOffset() & 0x1fff)) {
            log.debug("Not generating ICMP Unreachable for IP fragment packet")
            return false
        }
        return true
    }

    private def buildIcmpError(icmpType: Char, icmpCode: Any,
                   forMatch: WildcardMatch, forPacket: Ethernet) : ICMP = {
        val pktHere = forMatch.apply(forPacket)
        var ipPkt: IPv4 = null
        pktHere.getPayload match {
            case ip: IPv4 => ipPkt = ip
            case _ => return null
        }

        icmpCode match {
            case c: ICMP.EXCEEDED_CODE if icmpType == ICMP.TYPE_TIME_EXCEEDED =>
                val icmp = new ICMP()
                icmp.setTimeExceeded(c, ipPkt)
                return icmp
            case c: ICMP.UNREACH_CODE if icmpType == ICMP.TYPE_UNREACH =>
                val icmp = new ICMP()
                icmp.setUnreachable(c, ipPkt)
                return icmp
            case _ =>
                return null
        }
    }

    /**
     * Send an ICMP error message.
     *
     * @param ingressMatch
     *            The wildcard match that caused the message to be generated
     * @param packet
     *            The original packet that started the simulation
     */
     def sendIcmpError(ingressMatch: WildcardMatch, packet: Ethernet,
                       icmpType: Char, icmpCode: Any)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem)  {
        // Check whether the original packet is allowed to trigger ICMP.
        // TODO(pino, abel): do we need the packet as seen by the ingress to
        // this router?
        if (!canSendIcmp(packet, ingressMatch.getInputPortUUID))
            return
        // Build the ICMP packet from inside-out: ICMP, IPv4, Ethernet headers.
        val icmp = buildIcmpError(icmpType, icmpCode, ingressMatch, packet)
        if (icmp == null)
            return

        val ip = new IPv4()
        ip.setPayload(icmp)
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)
        // The nwDst is the source of triggering IPv4 as seen by this router.
        ip.setDestinationAddress(ingressMatch.getNetworkSource)
        // The nwSrc is the address of the ingress port.
        val portConfig: RouterPortConfig = getRouterPortConfig(
                ingressMatch.getInputPortUUID)
        if (null == portConfig) {
            log.error("Failed to retrieve the inPort's configuration {}",
                    ingressMatch.getInputPortUUID)
            return
        }
        ip.setSourceAddress(portConfig.portAddr)

        val eth = new Ethernet()
        eth.setPayload(ip)
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setSourceMACAddress(portConfig.getHwAddr)
        eth.setDestinationMACAddress(ingressMatch.getEthernetSource)

        /* log.debug("sendIcmpError from port {}, {} to {}", new Object[] {
                ingressMatch.getInputPortUUID,
                IPv4.fromIPv4Address(ip.getSourceAddress()),
                IPv4.fromIPv4Address(ip.getDestinationAddress()) }) */
        SimulationController.getRef(actorSystem) ! EmitGeneratedPacket(
            ingressMatch.getInputPortUUID, eth)
    }
}
