/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.simulation

import java.util.UUID

import scala.concurrent.ExecutionContext
import akka.actor.ActorSystem

import org.midonet.cluster.client.RouterPort
import org.midonet.midolman.{NotYetException, PacketsEntryPoint, Ready, Urgent}
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.Icmp._
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology.{RoutingTableWrapper, TagManager, RouterConfig}
import org.midonet.packets.{MAC, Unsigned, Ethernet, IPAddr}
import org.midonet.sdn.flows.{FlowTagger, WildcardMatch}
import org.midonet.odp.flows.IPFragmentType

/**
 * Defines the base Router device that is meant to be extended with specific
 * implementations for IPv4 and IPv6 that deal with version specific details
 * such as ARP vs. NDP.
 */
abstract class RouterBase[IP <: IPAddr](val id: UUID,
                                        val cfg: RouterConfig,
                                        val rTable: RoutingTableWrapper[IP],
                                        val routerMgrTagger: TagManager)
                                   (implicit system: ActorSystem,
                                             icmpErrors: IcmpErrorSender[IP])
    extends Coordinator.Device {

    import Coordinator._


    val validEthertypes: Set[Short]
    implicit val log = LoggerFactory.getSimulationAwareLog(
        this.getClass)(system.eventStream)

    val routeBalancer = new RouteBalancer(rTable)

    val deviceTag = FlowTagger.tagForDevice(id)

    protected def unsupportedPacketAction: Action

    /**
     * Process the packet. Will validate first the ethertype and ensure that
     * traffic is not vlan-tagged.
     *
     * @param pktContext The context for the simulation of this packet's
     *                   traversal of the virtual network. Use the context to
     *                   subscribe for notifications on the removal of any
     *                   resulting flows, or to tag any resulting flows for
     *                   indexing.
     * @return An instance of Action that reflects what the device would do
     *         after handling this packet (e.g. drop it, consume it, forward it)
     */
    override def process(pktContext: PacketContext)
                        (implicit ec: ExecutionContext): Urgent[Action] = {
        implicit val packetContext = pktContext

        if (!pktContext.wcmatch.getVlanIds.isEmpty) {
            log.info("Dropping VLAN tagged traffic")
            return Ready(DropAction)
        }

        if (!validEthertypes.contains(pktContext.wcmatch.getEtherType)) {
            log.info("Dropping unsupported EtherType {}",
                      pktContext.wcmatch.getEtherType)
            return Ready(unsupportedPacketAction)
        }

        getRouterPort(pktContext.inPortId, pktContext.expiry) flatMap {
            case inPort if !cfg.adminStateUp =>
                log.debug("Router {} state is down, DROP", id)
                sendAnswer(inPort.id, icmpErrors.unreachableProhibitedIcmp(
                    inPort, pktContext.wcmatch, pktContext.ethernet))
                pktContext.addFlowTag(deviceTag)
                Ready(DropAction)
            case inPort =>
                preRouting(inPort)
        }
    }

    private def handlePreRoutingResult(preRoutingResult: RuleResult,
                                       inPort: RouterPort)
                                      (implicit ec: ExecutionContext,
                                                pktContext: PacketContext)
    : Option[Action] = {
        preRoutingResult.action match {
            case RuleResult.Action.ACCEPT => // pass through
            case RuleResult.Action.DROP =>
                // For header fragments only, we should send an ICMP_FRAG_NEEDED
                // response and install a temporary drop flow so that the sender
                // will continue to receive these responses occasionally. We can
                // silently drop nonheader fragments and unfragmented packets.
                if (pktContext.wcmatch.getIpFragmentType != IPFragmentType.First)
                    return Some(DropAction)

                sendAnswer(inPort.id, icmpErrors.unreachableFragNeededIcmp(
                    inPort, pktContext.wcmatch, pktContext.ethernet))
                return Some(TemporaryDropAction)
            case RuleResult.Action.REJECT =>
                sendAnswer(inPort.id, icmpErrors.unreachableProhibitedIcmp(
                    inPort, pktContext.wcmatch, pktContext.ethernet))
                return Some(DropAction)
            case other =>
                log.error("Pre-routing for {} returned an action which was {}, " +
                    "not ACCEPT, DROP, or REJECT.", id, preRoutingResult.action)
                return Some(ErrorDropAction)
        }

        if (preRoutingResult.pmatch ne pktContext.wcmatch) {
            log.error("Pre-routing for {} returned a different match obj.", id)
            return Some(ErrorDropAction)
        }

        None
    }

    @throws[NotYetException]
    private def preRouting(inPort: RouterPort)
                          (implicit ec: ExecutionContext,
                                    pktContext: PacketContext)
    : Urgent[Action] = {

        val hwDst = pktContext.wcmatch.getEthernetDestination
        if (Ethernet.isBroadcast(hwDst)) {
            log.debug("Received an L2 broadcast packet.")
            return Ready(handleL2Broadcast(inPort))
        }

        if (hwDst != inPort.portMac) { // Not addressed to us, log.warn and drop
            log.warning("{} neither broadcast nor inPort's MAC ({})", hwDst,
                        inPort.portMac)
            return Ready(DropAction)
        }

        pktContext.addFlowTag(deviceTag)
        handleNeighbouring(inPort) match {
            case Some(a: Action) => return Ready(a)
            case None =>
        }

        pktContext.outPortId = null // input port should be set already

        val preRoutingAction = applyServicesInbound() match {
            case res if res.action == RuleResult.Action.CONTINUE =>
                // Continue to inFilter / ingress chain

                val inFilter = if (cfg.inboundFilter == null) null
                               else tryAsk[Chain](cfg.inboundFilter)
                handlePreRoutingResult(
                    Chain.apply(inFilter, pktContext, id, false), inPort
                )
            case res => // Skip the inFilter / ingress chain
                handlePreRoutingResult(res, inPort)
        }

        preRoutingAction match {
            case Some(a) => Ready(a)
            case None => routing(inPort)
        }

    }

    /**
     * This method will be executed after L2 processing and ARP handling,
     * bt before the inbound filter rules are applied.
     *
     * Currently just does load balancing.
     */
    @throws[NotYetException]
    protected def applyServicesInbound()(implicit  ec: ExecutionContext,
                                                   pktContext: PacketContext)
    : RuleResult = {
        if (cfg.loadBalancer == null)
            new RuleResult(RuleResult.Action.CONTINUE, null, pktContext.wcmatch)
        else
            tryAsk[LoadBalancer](cfg.loadBalancer).processInbound(pktContext)
    }


    private def routing(inPort: RouterPort)(implicit ec: ExecutionContext,
            context: PacketContext): Urgent[Action] = {

        val frame = context.ethernet
        val wcmatch = context.wcmatch
        val dstIP = context.wcmatch.getNetworkDestinationIP

        def applyTimeToLive: Option[Action] = {
            /* TODO(guillermo, pino): Have WildcardMatch take a DecTTLBy instead,
             * so that there need only be one sim. run for different TTLs.  */
            if (wcmatch.getNetworkTTL != null) {
                val ttl = Unsigned.unsign(wcmatch.getNetworkTTL)
                if (ttl <= 1) {
                    sendAnswer(inPort.id, icmpErrors.timeExceededIcmp(
                        inPort, wcmatch, frame))
                    return Some(DropAction)
                } else {
                    context.wcmatch.setNetworkTTL((ttl - 1).toByte)
                    return None
                }
            }

            None
        }

        def applyRoutingTable: (Route, Urgent[Action]) = {
            val rt: Route = routeBalancer.lookup(wcmatch)

            if (rt == null) {
                // No route to network
                log.debug("Route lookup: No route to network (dst:{}), {}",
                          dstIP, rTable.rTable)
                sendAnswer(inPort.id,
                    icmpErrors.unreachableNetIcmp(inPort, wcmatch, frame))
                return (rt, Ready(DropAction))
            }

            val action = rt.nextHop match {
                case Route.NextHop.LOCAL if isIcmpEchoRequest(wcmatch) =>
                    log.debug("Got ICMP echo req, will reply")
                    sendIcmpEchoReply(wcmatch, frame, context.expiry) map {
                        _ => ConsumedAction
                    }

                case Route.NextHop.LOCAL =>
                    log.debug("Dropping non icmp_req addressed to local port")
                    Ready(TemporaryDropAction)

                case Route.NextHop.BLACKHOLE =>
                    log.debug("Dropping packet, BLACKHOLE route (dst:{})",
                        wcmatch.getNetworkDestinationIP)
                    Ready(TemporaryDropAction)

                case Route.NextHop.REJECT =>
                    sendAnswer(inPort.id, icmpErrors.unreachableProhibitedIcmp(
                        inPort, wcmatch, frame))
                    log.debug("Dropping packet, REJECT route (dst:{})",
                        wcmatch.getNetworkDestinationIP)
                    Ready(DropAction)

                case Route.NextHop.PORT if rt.nextHopPort == null =>
                    log.error("Routing table lookup for {} forwarded to port " +
                              "null.", dstIP)
                    // TODO(pino): should we remove this route?
                    Ready(DropAction)

                case Route.NextHop.PORT =>
                    Ready(ToPortAction(rt.nextHopPort))

                case _ =>
                    log.error("Routing table lookup for {} returned invalid " +
                        "nextHop of {}", dstIP, rt.nextHop)
                    // rt.nextHop is invalid. The only way the simulation result
                    // would change is if there are other matching routes that are
                    // 'sane'. If such routes were created, this flow will be
                    // invalidated. Thus, we can return DropAction and not
                    // ErrorDropAction
                    Ready(DropAction)
            }

            (rt, action)
        }

        def applyTagsForRoute(route: Route, action: Action): Unit = action match {
            case a@TemporaryDropAction =>
            case a@ConsumedAction =>
            case a@ErrorDropAction =>
            case a => // We don't want to tag a temporary flow (e.g. created by
                      // a BLACKHOLE route), and we do that to avoid excessive
                      // interaction with the RouterManager, who needs to keep
                      // track of every IP address the router gives to it.
                if (route != null) {
                    context.addFlowTag(FlowTagger.tagForRoute(route))
                }
                context.addFlowTag(FlowTagger.tagForDestinationIp(id, dstIP))
                routerMgrTagger.addTag(dstIP)
                context.addFlowRemovedCallback(
                    routerMgrTagger.getFlowRemovalCallback(dstIP))
        }

        val (rt, action) = applyTimeToLive match {
            case Some(a) => (null, Ready(a))
            case None => applyRoutingTable
        }

        if (action.isReady)
            applyTagsForRoute(rt, action.get)

        action flatMap {
            case ToPortAction(outPortId) =>
                 getRouterPort(outPortId, context.expiry) flatMap {
                    case outPort => postRouting(inPort, outPort, rt, context)
                }
            case a => action
        }
    }

    // POST ROUTING
    @throws[NotYetException]
    private def postRouting(inPort: RouterPort, outPort: RouterPort,
                            rt: Route, pktContext: PacketContext)
                           (implicit ec: ExecutionContext)
    : Urgent[Action] = {

        implicit val packetContext = pktContext

        val pMatch = pktContext.wcmatch
        val pFrame = pktContext.ethernet

        pktContext.outPortId = outPort.id

        val postRoutingResult = applyServicesOutbound() match {
            case res if res.action == RuleResult.Action.CONTINUE =>
                // Continue to outFilter / egress chain
                val outFilter = if (cfg.outboundFilter == null) null
                                else tryAsk[Chain](cfg.outboundFilter)
                Chain.apply(outFilter, pktContext, id, false)
            case res => res // Skip outFilter / egress chain
        }

        postRoutingResult.action match {
            case RuleResult.Action.ACCEPT => // pass through
            case RuleResult.Action.DROP =>
                log.debug("PostRouting DROP rule")
                return Ready(DropAction)
            case RuleResult.Action.REJECT =>
                log.debug("PostRouting REJECT rule")
                sendAnswer(inPort.id, icmpErrors.unreachableProhibitedIcmp(
                    inPort, pMatch, pFrame))
                return Ready(DropAction)
            case other =>
                log.error("Post-routing for {} returned {} which was not " +
                          "ACCEPT, DROP or REJECT.", id, other)
                return Ready(DropAction)
        }

        if (postRoutingResult.pmatch ne pMatch) {
            log.error("Post-routing for {} returned a different match obj.", id)
            return Ready(ErrorDropAction)
        }
        if (pMatch.getNetworkDestinationIP == outPort.portAddr.getAddress) {
            log.error("Packet addressed to port without a LOCAL route")
            return Ready(DropAction)
        }

        getNextHopMac(outPort, rt,
                      pMatch.getNetworkDestinationIP.asInstanceOf[IP],
                      pktContext.expiry) map {
            case null if rt.nextHopGateway == 0 || rt.nextHopGateway == -1 =>
                log.debug("icmp host unreachable, host mac unknown")
                sendAnswer(inPort.id, icmpErrors.unreachableHostIcmp(
                    inPort, pMatch, pFrame))
                ErrorDropAction
            case null =>
                log.debug("icmp net unreachable, gw mac unknown")
                sendAnswer(inPort.id, icmpErrors.unreachableNetIcmp(
                    inPort, pMatch, pFrame))
                ErrorDropAction
            case nextHopMac =>
                log.debug("routing packet to {}", nextHopMac)
                pMatch.setEthernetSource(outPort.portMac)
                pMatch.setEthernetDestination(nextHopMac)
                new ToPortAction(rt.nextHopPort)
        }

    }

    def sendAnswer(portId: UUID, eth: Option[Ethernet])
                      (implicit pktContext: PacketContext) {
        if (eth.nonEmpty)
            PacketsEntryPoint !
                EmitGeneratedPacket(portId, eth.get, pktContext.flowCookie)
    }

    /**
     * This method will be executed after outbound filter rules are applied.
     *
     * Currently just does load balancing.
     */
    @throws[NotYetException]
    protected def applyServicesOutbound()(implicit ec: ExecutionContext,
                                                   pktContext: PacketContext)
    : RuleResult =
        if (cfg.loadBalancer == null) {
            new RuleResult(RuleResult.Action.CONTINUE, null, pktContext.wcmatch)
        } else {
            tryAsk[LoadBalancer](cfg.loadBalancer).processOutbound(pktContext)
        }

    final protected def getRouterPort(portID: UUID, expiry: Long)
                                     (implicit pktContext: PacketContext) =
        expiringAsk[RouterPort](portID, log, expiry)

    // Auxiliary, IP version specific abstract methods.

    /**
     * Given a route and a destination address, return the MAC address of
     * the next hop (or the destination's if it's a link-local destination)
     *
     * @param rt Route that the packet will be sent through
     * @param ipDest Final destination of the packet to be sent
     * @param expiry expiration time for the request
     * @return
     */
    protected def getNextHopMac(outPort: RouterPort, rt: Route,
                                ipDest: IP, expiry: Long)
                               (implicit ec: ExecutionContext,
                                         pktContext: PacketContext): Urgent[MAC]

    /**
     * Will be called to construct an ICMP echo reply for an ICMP echo reply
     * contained in the given packet. Returns a Ready telling whether the reply
     * was finally sent or not, or a NotYet if some required data was not
     * locally cached.
     */
    protected def sendIcmpEchoReply(ingressMatch: WildcardMatch,
                                    packet: Ethernet, expiry: Long)
                               (implicit ec: ExecutionContext,
                                         pktCtx: PacketContext): Urgent[Boolean]

    /**
     * Will be called from the pre-routing process immediately after receiving
     * the frame, if Ethernet.isBroadcast(hwDst).
     */
    protected def handleL2Broadcast(inPort: RouterPort)
                                   (implicit ec: ExecutionContext,
                                             pktCtx: PacketContext): Action

    /**
     * This method will be executed after basic L2 processing is done,
     * including handling broadcasts and reacting to frames not addressed to
     * our MAC.
     */
    protected def handleNeighbouring(inPort: RouterPort)
                                    (implicit ec: ExecutionContext,
                                          pktCtx: PacketContext): Option[Action]

    protected def isIcmpEchoRequest(mmatch: WildcardMatch): Boolean
}
