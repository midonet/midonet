/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.simulation

import akka.actor.{ActorContext, ActorSystem}
import akka.dispatch.{ExecutionContext, Future, Promise}
import java.util.UUID

import org.midonet.midolman.DeduplicationActor
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.rules.RuleResult.{Action => RuleAction}
import org.midonet.midolman.simulation.Coordinator._
import org.midonet.midolman.topology._
import org.midonet.cluster.client._
import org.midonet.packets._
import org.midonet.packets.ICMP.{EXCEEDED_CODE, UNREACH_CODE}
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.simulation.Coordinator.ConsumedAction
import org.midonet.midolman.simulation.Coordinator.DropAction
import org.midonet.midolman.simulation.Coordinator.ToPortAction
import org.midonet.midolman.simulation.Coordinator.ErrorDropAction
import org.midonet.midolman.simulation.Coordinator.NotIPv4Action
import org.midonet.midolman.rules.RuleResult

class Router(val id: UUID, val cfg: RouterConfig,
             val rTable: RoutingTableWrapper, val arpTable: ArpTable,
             val inFilter: Chain, val outFilter: Chain,
             val routerMgrTagger: TagManager)
            (implicit context: ActorContext) extends Device {
    val log =  LoggerFactory.getSimulationAwareLog(this.getClass)(context.system.eventStream)
    private val loadBalancer = new LoadBalancer(rTable)

    override def process(pktContext: PacketContext)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem
                         ): Future[Action] = {
        implicit val packetContext = pktContext

        if (pktContext.getMatch.getEtherType != IPv4.ETHERTYPE &&
                pktContext.getMatch.getEtherType != ARP.ETHERTYPE)
            return Promise.successful(new NotIPv4Action)(ec)

        getRouterPort(pktContext.getInPortId, pktContext.getExpiry) flatMap {
            case null => log.debug("Router - in port {} was null",
                             pktContext.getInPortId())
                          Promise.successful(new DropAction)(ec)
            case inPort => preRouting(pktContext, inPort)
        }
    }

    private def processArp(pkt: IPacket, inPort: RouterPort[_])
                          (implicit ec: ExecutionContext,
                           actorSystem: ActorSystem,
                           originalPktContex: PacketContext): Action = pkt match {

        case arp: ARP =>
            arp.getOpCode match {
                case ARP.OP_REQUEST =>
                    processArpRequest(arp, inPort)
                    new ConsumedAction
                case ARP.OP_REPLY =>
                    processArpReply(arp, inPort)
                    new ConsumedAction
                case _ =>
                    new DropAction
            }
        case badType =>
            log.warning("Non-ARP packet with ethertype ARP: {}", badType)(null)
            new DropAction
    }

    /* Does pre-routing phase.  Delegates routing, post-routing, and emit
     * phases to routing() and postRouting().
     */
    private def preRouting(pktContext: PacketContext, inPort: RouterPort[_])
                          (implicit ec: ExecutionContext,
                           actorSystem: ActorSystem): Future[Action] = {

        implicit val packetContext = pktContext
        pktContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(id))

        val hwDst = pktContext.getMatch.getEthernetDestination
        if (Ethernet.isBroadcast(hwDst)) {
            log.debug("Received an L2 broadcast packet.")
            // Broadcast packet:  Handle if ARP, drop otherwise.
            val payload = pktContext.getFrame.getPayload
            if (pktContext.getMatch.getEtherType == ARP.ETHERTYPE)
                return Promise.successful(processArp(payload, inPort))(ec)
            else
                return Promise.successful(new DropAction)(ec)
        }

        if (hwDst != inPort.portMac) {
            // Not addressed to us, log.warn and drop.
            log.warning("{} neither broadcast nor inPort's MAC ({})", hwDst,
                     inPort.portMac)
            return Promise.successful(new DropAction)(ec)
        }

        if (pktContext.getMatch.getEtherType == ARP.ETHERTYPE) {
            // Non-broadcast ARP.  Handle reply, drop rest.
            val payload = pktContext.getFrame.getPayload
            return Promise.successful(processArp(payload, inPort))(ec)
        }

        // Apply the pre-routing (ingress) chain
        // InputPort already set.
        pktContext.setOutputPort(null)
        val preRoutingResult = Chain.apply(inFilter, pktContext,
                                           pktContext.getMatch, id, false)
        if (preRoutingResult.action == RuleAction.DROP)
            return Promise.successful(new DropAction)(ec)
        else if (preRoutingResult.action == RuleAction.REJECT) {
            sendIcmpError(inPort, pktContext.getMatch, pktContext.getFrame,
                ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_FILTER_PROHIB)
            return Promise.successful(new DropAction)(ec)
        } else if (preRoutingResult.action != RuleAction.ACCEPT) {
            log.error("Pre-routing for {} returned an action which was {}, " +
                      "not ACCEPT, DROP, or REJECT.", id,
                      preRoutingResult.action)
            return Promise.successful(new ErrorDropAction)(ec)
        }
        if (preRoutingResult.pmatch ne pktContext.getMatch) {
            log.error("Pre-routing for {} returned a different match object.",
                      id)
            return Promise.successful(new ErrorDropAction)(ec)
        }

        routing(pktContext, inPort)
    }

    private def routing(pktContext: PacketContext, inPort: RouterPort[_])
                          (implicit ec: ExecutionContext,
                           actorSystem: ActorSystem): Future[Action] = {

        implicit val packetContext = pktContext
        /* TODO(D-release): Have WildcardMatch take a DecTTLBy instead,
         * so that there need only be one sim. run for different TTLs.  */
        if (pktContext.getMatch.getNetworkTTL != null) {
            val ttl = Unsigned.unsign(pktContext.getMatch.getNetworkTTL)
            if (ttl <= 1) {
                sendIcmpError(inPort, pktContext.getMatch, pktContext.getFrame,
                    ICMP.TYPE_TIME_EXCEEDED, EXCEEDED_CODE.EXCEEDED_TTL)
                return Promise.successful(new DropAction)(ec)
            } else {
                pktContext.getMatch.setNetworkTTL((ttl - 1).toByte)
            }
        }


        // tag using the destination IP
        val dstIP = pktContext.getMatch.getNetworkDestinationIP
        pktContext.addFlowTag(FlowTagger.invalidateByIp(id, dstIP))
        // pass tag to the RouterManager so that it will be able to invalidate
        // the flow
        routerMgrTagger.addTag(dstIP)
        // register the tag removal callback
        pktContext.addFlowRemovedCallback(
            routerMgrTagger.getFlowRemovalCallback(dstIP))

        val rt: Route = loadBalancer.lookup(pktContext.getMatch)
        if (rt == null) {
            // No route to network
            log.debug("Route lookup: No route to network (dst:{}), {}",
                      dstIP, rTable.rTable)
            sendIcmpError(inPort, pktContext.getMatch, pktContext.getFrame,
                ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_NET)
            return Promise.successful(new DropAction)(ec)
        }

        // tag using this route
        pktContext.addFlowTag(FlowTagger.invalidateByRoute(id, rt.hashCode()))

        rt.nextHop match {
            case Route.NextHop.LOCAL =>
                if (isIcmpEchoRequest(pktContext.getMatch)) {
                    log.debug("got ICMP echo")
                    sendIcmpEchoReply(pktContext.getMatch, pktContext.getFrame,
                        pktContext.getExpiry)
                    Promise.successful(new ConsumedAction)(ec)
                } else {
                    Promise.successful(new DropAction)(ec)
                }

            case Route.NextHop.BLACKHOLE =>
                log.debug("Dropping packet, BLACKHOLE route (dst:{})",
                    pktContext.getMatch.getNetworkDestinationIP)
                Promise.successful(new DropAction)(ec)

            case Route.NextHop.REJECT =>
                sendIcmpError(inPort, pktContext.getMatch, pktContext.getFrame,
                    ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_FILTER_PROHIB)
                log.debug("Dropping packet, REJECT route (dst:{})",
                    pktContext.getMatch.getNetworkDestinationIP)
                Promise.successful(new DropAction)(ec)

            case Route.NextHop.PORT =>
                if (rt.nextHopPort == null) {
                    log.error("Routing table lookup for {} forwarded to port " +
                        "null.", dstIP)
                    // TODO(pino): should we remove this route?
                    Promise.successful(new DropAction)(ec)
                } else {
                    getRouterPort(rt.nextHopPort, pktContext.getExpiry) flatMap {
                        case null =>
                            Promise.successful(new ErrorDropAction)(ec)
                        case outPort =>
                            postRouting(inPort, outPort, rt, pktContext)
                    }
                }

            case _ =>
                log.error("Routing table lookup for {} returned invalid " +
                    "nextHop of {}", dstIP, rt.nextHop)
                // rt.nextHop is invalid. The only way the simulation result
                // would change is if there are other matching routes that are
                // 'sane'. If such routes were created, this flow will be
                // invalidated. Thus, we can return DropAction and not
                // ErrorDropAction
                Promise.successful(new DropAction)(ec)
        }
    }

    private def postRouting(inPort: RouterPort[_], outPort: RouterPort[_],
                            rt: Route, pktContext: PacketContext)
                           (implicit ec: ExecutionContext,
                            actorSystem: ActorSystem): Future[Action] = {

        implicit val packetContext = pktContext
        pktContext.setOutputPort(outPort.id)
        val postRoutingResult = Chain.apply(outFilter, pktContext,
                                            pktContext.getMatch, id, false)
        if (postRoutingResult.action == RuleAction.DROP) {
            log.debug("PostRouting DROP rule")
            return Promise.successful(new DropAction)
        } else if (postRoutingResult.action == RuleAction.REJECT) {
            log.debug("PostRouting REJECT rule")
            sendIcmpError(inPort, pktContext.getMatch, pktContext.getFrame,
                ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_FILTER_PROHIB)
            return Promise.successful(new DropAction)
        } else if (postRoutingResult.action != RuleAction.ACCEPT) {
            log.error("Post-routing for {} returned an action which was {}, " +
                      "not ACCEPT, DROP, or REJECT.", id,
                      postRoutingResult.action)
            return Promise.successful(new ErrorDropAction)
        }
        if (postRoutingResult.pmatch ne pktContext.getMatch) {
            log.error("Post-routing for {} returned a different match object.",
                      id)
            return Promise.successful(new ErrorDropAction)(ec)
        }

        if (pktContext.getMatch.getNetworkDestinationIP ==
                                     outPort.portAddr.getAddress) {
            log.error("Got a packet addressed to a port without a LOCAL route")
            return Promise.successful(new DropAction)(ec)
        }

        // Set HWSrc
        pktContext.getMatch.setEthernetSource(outPort.portMac)
        // Set HWDst
        val macFuture = getNextHopMac(outPort, rt,
                                      pktContext.getMatch.getNetworkDestinationIP,
                                      pktContext.getExpiry)
        macFuture map {
            case null =>
                if (rt.nextHopGateway == 0 || rt.nextHopGateway == -1) {
                    log.debug("icmp host unreachable, host mac unknown")
                    sendIcmpError(inPort, pktContext.getMatch,
                                  pktContext.getFrame, ICMP.TYPE_UNREACH,
                                  UNREACH_CODE.UNREACH_HOST)
                } else {
                    log.debug("icmp net unreachable, gw mac unknown")
                    sendIcmpError(inPort, pktContext.getMatch,
                                  pktContext.getFrame, ICMP.TYPE_UNREACH,
                                  UNREACH_CODE.UNREACH_NET)
                }
                new ErrorDropAction: Action
            case nextHopMac =>
                log.debug("routing packet to {}", nextHopMac)
                pktContext.getMatch.setEthernetDestination(nextHopMac)
                new ToPortAction(rt.nextHopPort): Action
        }
    }

    private def getRouterPort(portID: UUID, expiry: Long)
            (implicit actorSystem: ActorSystem,
             pktContext: PacketContext): Future[RouterPort[_]] = {
        expiringAsk(PortRequest(portID, false), expiry)
            .mapTo[RouterPort[_]] map {
            case null =>
                log.error("Can't find router port: {}", portID)
                null
            case rtrPort => rtrPort
        }
    }

    private def processArpRequest(pkt: ARP, inPort: RouterPort[_])
                                 (implicit ec: ExecutionContext,
                                  actorSystem: ActorSystem,
                                  originalPktContex: PacketContext) {

        if (pkt.getProtocolType != ARP.PROTO_TYPE_IP)
            return

        val tpa = IntIPv4.fromBytes(pkt.getTargetProtocolAddress)
        val spa = IntIPv4.fromBytes(pkt.getSenderProtocolAddress)
        val tha = pkt.getTargetHardwareAddress
        val sha = pkt.getSenderHardwareAddress

        if (!inPort.portAddr.containsAddress(IPAddr.fromIntIPv4(spa))) {
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
        if (!inPort.portAddr.toIntIPv4.unicastEquals(tpa)) {
            log.debug("Ignoring ARP Request to dst ip {} instead of " +
                "inPort's {}", tpa, inPort.portAddr)
            return
        }

        // Attempt to refresh the router's arp table.
        arpTable.set(spa, pkt.getSenderHardwareAddress)

        log.debug("replying to ARP request from {} for {} with own mac {}",
            Array[Object](spa, tpa, inPort.portMac))

        // Construct the reply, reversing src/dst fields from the request.
        val eth = ARP.makeArpReply(
            inPort.portMac, sha,
            pkt.getTargetProtocolAddress, pkt.getSenderProtocolAddress);
        DeduplicationActor.getRef(actorSystem) ! EmitGeneratedPacket(
            inPort.id, eth,
            if (originalPktContex != null) Option(originalPktContex.getFlowCookie) else None)
    }

    private def processArpReply(pkt: ARP, port: RouterPort[_])
                               (implicit actorSystem: ActorSystem,
                                pktContext: PacketContext) {

        def isGratuitous(tha: MAC, tpa: IntIPv4,
                         sha: MAC, spa: IntIPv4): Boolean = {
            (tpa == spa && tha == sha)
        }

        def isAddressedToThis(tha: MAC, tpa: IntIPv4): Boolean = {
            (port.portAddr.toIntIPv4.unicastEquals(tpa) && tha == port.portMac)
        }

        // Verify the reply:  It's addressed to our MAC & IP, and is about
        // the MAC for an IPv4 address.
        if (pkt.getHardwareType != ARP.HW_TYPE_ETHERNET ||
                pkt.getProtocolType != ARP.PROTO_TYPE_IP) {
            log.debug("Router {} ignoring ARP reply on port {} because hwtype "+
                      "wasn't Ethernet or prototype wasn't IPv4.", id, port.id)
            return
        }

        val tpa = IntIPv4.fromBytes(pkt.getTargetProtocolAddress)
        val tha: MAC = pkt.getTargetHardwareAddress
        val spa = IntIPv4.fromBytes(pkt.getSenderProtocolAddress)
        val sha: MAC = pkt.getSenderHardwareAddress

        if (isGratuitous(tha, tpa, sha, spa)) {
            log.debug("Router {} got a gratuitous ARP reply from {}", id, spa)
        } else if (!isAddressedToThis(tha, tpa)) {
            // The ARP is not gratuitous, so it should be intended for us.
            log.debug("Router {} ignoring ARP reply on port {} because tpa or "+
                      "tha doesn't match.", id, port.id)
            return
        } else {
            log.debug("Router {} received an ARP reply from {}", id, spa)
        }

        // Question:  Should we check if the ARP reply disagrees with an
        // existing cache entry and make noise if so?
        if (!port.portAddr.containsAddress(IPAddr.fromIntIPv4(spa))) {
            log.debug("Ignoring ARP reply from address {} not in the ingress " +
                "port network {}", spa, port.portAddr)
            return
        }

        arpTable.set(spa, sha)
    }

    private def isIcmpEchoRequest(mmatch: WildcardMatch): Boolean = {
        mmatch.getNetworkProtocol == ICMP.PROTOCOL_NUMBER &&
            (mmatch.getTransportSource & 0xff) == ICMP.TYPE_ECHO_REQUEST &&
            (mmatch.getTransportDestination & 0xff) == ICMP.CODE_NONE
    }

    private def sendIcmpEchoReply(ingressMatch: WildcardMatch, packet: Ethernet,
                                  expiry: Long)
                    (implicit ec: ExecutionContext, actorSystem: ActorSystem,
                     packetContext: PacketContext) {
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
        ip.setDestinationAddress(ingressMatch.getNetworkSourceIP.asInstanceOf[IPv4Addr])
        ip.setSourceAddress(ingressMatch.getNetworkDestinationIP.asInstanceOf[IPv4Addr])
        ip.setPayload(reply)

        sendIPPacket(ip, expiry)
    }

    private def getPeerMac(rtrPort: InteriorRouterPort, expiry: Long)
                          (implicit ec: ExecutionContext,
                           actorSystem: ActorSystem,
                           pktContext: PacketContext): Future[MAC] = {
        val peerPortFuture = expiringAsk(
                PortRequest(rtrPort.peerID, false), expiry).mapTo[Port[_]]
        peerPortFuture map {
            case null =>
                log.error("getPeerMac: cannot get port {}", rtrPort.peerID)
                null
            case rp: RouterPort[_] =>
                rp.portMac
            case nrp =>
                log.debug("getPeerMac asked for MAC of non-router port {}", nrp)
                null
        }
    }

    private def getMacForIP(port: RouterPort[_], nextHopIP: IPAddr,
                            expiry: Long)
                           (implicit ec: ExecutionContext,
                            actorSystem: ActorSystem,
                            pktContext: PacketContext): Future[MAC] = {
        port match {
            case extPort: ExteriorRouterPort =>
                extPort.nwSubnet match {
                    case extAddr: IPv4Subnet =>
                        if (!extAddr.containsAddress(nextHopIP)) {
                            log.warning("getMacForIP: cannot get MAC for {} - "+
                                "address not in network segment of port {} ({})",
                                nextHopIP, port.id, extAddr)
                            return Promise.successful(null)(ec)
                        }
                    case _ =>
                        return Promise.failed(new IllegalArgumentException)
                }
            case _ => /* Fall through */
        }
        arpTable.get(nextHopIP.toIntIPv4, port, expiry)
    }

    /**
     * Given a route and a destination address, return the MAC address of
     * the next hop (or the destination's if it's a link-local destination)
     *
     * @param rt Route that the packet will be sent through
     * @param ipDest Final destination of the packet to be sent
     * @param expiry
     * @param ec
     * @return
     */
    private def getNextHopMac(outPort: RouterPort[_], rt: Route,
                              ipDest: IPAddr, expiry: Long)
                             (implicit ec: ExecutionContext,
                              actorSystem: ActorSystem,
                              pktContext: PacketContext): Future[MAC] = {
        if (outPort == null)
            return Promise.successful(null)(ec)
        var peerMacFuture: Future[MAC] = null
        outPort match {
            case interior: InteriorRouterPort =>
                if (interior.peerID == null) {
                    log.warning("Packet sent to dangling logical port {}",
                        rt.nextHopPort)
                    return Promise.successful(null)(ec)
                }
                peerMacFuture = getPeerMac(interior, expiry)
            case _ => /* Fall through to ARP'ing below. */
                peerMacFuture = Promise.successful(null)(ec)
        }

        val nextHopInt: Int = rt.nextHopGateway
        var nextHopIP: IPAddr =
            if (nextHopInt == 0 || nextHopInt == -1) {  /* Last hop */
                ipDest
            } else {
                new IPv4Addr().setIntAddress(nextHopInt)
            }
        peerMacFuture flatMap {
            case null => getMacForIP(outPort, nextHopIP, expiry)
            case mac => Promise.successful(mac)
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
                    (implicit ec: ExecutionContext, actorSystem: ActorSystem,
                     packetContext: PacketContext) {

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

        def _sendIPPacket(outPort: RouterPort[_], rt: Route) {
            if (packet.getDestinationIPAddress == outPort.portAddr.getAddress) {
                /* should never happen: it means we are trying to send a packet
                 * to ourselves, probably means that somebody sent an IP packet
                 * with a forged source address belonging to this router.
                 */
                log.error("Router {} trying to send a packet {} to itself.",
                          id, packet)
                return
            }

            val macFuture = getNextHopMac(outPort, rt,
                                packet.getDestinationIPAddress, expiry)
            macFuture onSuccess {
                case null =>
                    log.error("Failed to get MAC address to emit local packet")
                case mac =>
                    val eth = (new Ethernet()).setEtherType(IPv4.ETHERTYPE)
                    eth.setPayload(packet)
                    eth.setSourceMACAddress(outPort.portMac)
                    eth.setDestinationMACAddress(mac)
                    // Apply post-routing (egress) chain.
                    val egrMatch = WildcardMatch.fromEthernetPacket(eth)
                    val egrPktContext =
                        new PacketContext(null, eth, 0, null, true, None)
                    egrPktContext.setOutputPort(outPort.id)
                    val postRoutingResult = Chain.apply(outFilter,
                                       egrPktContext, egrMatch, id, false)
                    _applyPostActions(eth, postRoutingResult)

                    if (postRoutingResult.action == RuleAction.ACCEPT) {
                        DeduplicationActor.getRef(actorSystem).tell(
                            EmitGeneratedPacket(rt.nextHopPort, eth,
                                if (packetContext != null)
                                    Option(packetContext.getFlowCookie)
                                else None))
                    } else if (postRoutingResult.action != RuleAction.DROP &&
                               postRoutingResult.action != RuleAction.REJECT) {
                        log.error("Post-routing for {} returned an action " +
                                  "which was {}, not ACCEPT, DROP, or REJECT.",
                                  id, postRoutingResult.action)
                    }
            }
        }

        val ipMatch = (new WildcardMatch()).
                setNetworkDestination(packet.getDestinationIPAddress).
                setNetworkSource(packet.getSourceIPAddress)
        val rt: Route = loadBalancer.lookup(ipMatch)
        if (rt == null || rt.nextHop != Route.NextHop.PORT)
            return
        if (rt.nextHopPort == null)
            return

        getRouterPort(rt.nextHopPort, expiry) onSuccess {
            case null => log.error("Failed to get port to emit local packet")
            case outPort => _sendIPPacket(outPort, rt)
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
     * @param outPort
     *            If known, this is the port that would have emitted the packet.
     *            It's used to determine whether the packet was addressed to an
     *            IP (local subnet) broadcast address.
     * @return True if-and-only-if the packet meets none of the above conditions
     *         - i.e. it can trigger an ICMP error message.
     */
     def canSendIcmp(ethPkt: Ethernet, outPort: RouterPort[_])
                    (implicit pktContext: PacketContext) : Boolean = {
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
        if (ipPkt.isMcast) {
            log.debug("Not generating ICMP Unreachable for packet to an IP "
                    + "multicast address.")
            return false
        }
        // Ignore packets sent to the local-subnet IP broadcast address of the
        // intended egress port.
        if (null != outPort && outPort.portAddr.isInstanceOf[IPv4Subnet] &&
                ipPkt.getDestinationIPAddress ==
                    outPort.portAddr.asInstanceOf[IPv4Subnet]
                           .toBroadcastAddress) {
            log.debug("Not generating ICMP Unreachable for packet to "
                      + "the subnet local broadcast address.")
            return false
        }
        // Ignore packets to Ethernet broadcast and multicast addresses.
        if (ethPkt.isMcast) {
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
        if (0 != (ipPkt.getFragmentOffset & 0x1fff)) {
            log.debug("Not generating ICMP Unreachable for IP fragment packet")
            return false
        }
        return true
    }

    private def buildIcmpError(icmpType: Char, icmpCode: Any,
                   forMatch: WildcardMatch, forPacket: Ethernet) : ICMP = {
        // TODO(pino, guillermo, jlm): original or modified trigger pkt?
        val pktHere = forPacket //forMatch.apply(forPacket)
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
     def sendIcmpError(inPort: RouterPort[_], ingressMatch: WildcardMatch,
                       packet: Ethernet, icmpType: Char, icmpCode: Any)
                      (implicit ec: ExecutionContext,
                       actorSystem: ActorSystem,
                       pktContext: PacketContext) {
        log.debug("Prepare an ICMP in response to {}", packet)
        // Check whether the original packet is allowed to trigger ICMP.
        if (inPort == null) {
            log.debug("Don't send ICMP since inPort is null.")
            return
        }
        if (!canSendIcmp(packet, inPort)) {
            log.debug("ICMP not allowed for this packet.")
            return
        }
        // Build the ICMP packet from inside-out: ICMP, IPv4, Ethernet headers.
        log.debug("Generating ICMP error {}:{}", icmpType, icmpCode)
        val icmp = buildIcmpError(icmpType, icmpCode, ingressMatch, packet)
        if (icmp == null)
            return

        val ip = new IPv4()
        ip.setPayload(icmp)
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)
        // The nwDst is the source of triggering IPv4 as seen by this router.
        ip.setDestinationAddress(ingressMatch.getNetworkSourceIP
                                             .asInstanceOf[IPv4Addr])
        // The nwSrc is the address of the ingress port.
        ip.setSourceAddress(inPort.portAddr.getAddress.asInstanceOf[IPv4Addr])
        val eth = new Ethernet()
        eth.setPayload(ip)
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setSourceMACAddress(inPort.portMac)
        eth.setDestinationMACAddress(ingressMatch.getEthernetSource)

        /* log.debug("sendIcmpError from port {}, {} to {}", new Object[] {
                ingressMatch.getInputPortUUID,
                IPv4.fromIPv4Address(ip.getSourceAddress()),
                IPv4.fromIPv4Address(ip.getDestinationAddress()) }) */
        DeduplicationActor.getRef(actorSystem) ! EmitGeneratedPacket(
        // TODO(pino): check with Guillermo about match's vs. device's inPort.
            //ingressMatch.getInputPortUUID, eth)
            inPort.id, eth,
            if (pktContext != null) Option(pktContext.getFlowCookie) else None)
    }
}
