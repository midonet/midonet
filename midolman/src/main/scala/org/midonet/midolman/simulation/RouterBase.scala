/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.simulation

import java.util.UUID

import akka.actor.ActorSystem

import org.midonet.cluster.client.RouterPort
import org.midonet.midolman.{NotYetException, PacketsEntryPoint}
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.layer3.Route
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
    val routeBalancer = new RouteBalancer(rTable)
    val deviceTag = FlowTagger.tagForDevice(id)

    protected def unsupportedPacketAction: Action

    /**
     * Process the packet. Will validate first the ethertype and ensure that
     * traffic is not vlan-tagged.
     *
     * @param context The context for the simulation of this packet's
     *                   traversal of the virtual network. Use the context to
     *                   subscribe for notifications on the removal of any
     *                   resulting flows, or to tag any resulting flows for
     *                   indexing.
     * @return An instance of Action that reflects what the device would do
     *         after handling this packet (e.g. drop it, consume it, forward it)
     */
    override def process(context: PacketContext): Action = {
        implicit val packetContext = context

        if (!context.wcmatch.getVlanIds.isEmpty) {
            context.log.debug("Dropping VLAN tagged traffic")
            return DropAction
        }

        if (!validEthertypes.contains(context.wcmatch.getEtherType)) {
            context.log.debug("Dropping unsupported EtherType {}",
                              context.wcmatch.getEtherType)
            return unsupportedPacketAction
        }

        tryAsk[RouterPort](context.inPortId) match {
            case inPort if !cfg.adminStateUp =>
                context.log.debug("Router {} state is down, DROP", id)
                sendAnswer(inPort.id, icmpErrors.unreachableProhibitedIcmp(
                    inPort, context.wcmatch, context.ethernet))
                context.addFlowTag(deviceTag)
                DropAction
            case inPort =>
                preRouting(inPort)
        }
    }

    private def handlePreRoutingResult(preRoutingResult: RuleResult,
                                       inPort: RouterPort)
                                      (implicit context: PacketContext)
    : Option[Action] = {
        preRoutingResult.action match {
            case RuleResult.Action.ACCEPT => // pass through
            case RuleResult.Action.DROP =>
                // For header fragments only, we should send an ICMP_FRAG_NEEDED
                // response and install a temporary drop flow so that the sender
                // will continue to receive these responses occasionally. We can
                // silently drop nonheader fragments and unfragmented packets.
                if (context.wcmatch.getIpFragmentType != IPFragmentType.First)
                    return Some(DropAction)

                sendAnswer(inPort.id, icmpErrors.unreachableFragNeededIcmp(
                    inPort, context.wcmatch, context.ethernet))
                return Some(TemporaryDropAction)
            case RuleResult.Action.REJECT =>
                sendAnswer(inPort.id, icmpErrors.unreachableProhibitedIcmp(
                    inPort, context.wcmatch, context.ethernet))
                return Some(DropAction)
            case other =>
                context.log.warn("Pre-routing returned an action which was {}, " +
                                 "not ACCEPT, DROP, or REJECT.", preRoutingResult.action)
                return Some(ErrorDropAction)
        }

        if (preRoutingResult.pmatch ne context.wcmatch) {
            context.log.warn("Pre-routing for {} returned a different match obj.", id)
            return Some(ErrorDropAction)
        }

        None
    }

    @throws[NotYetException]
    private def preRouting(inPort: RouterPort)
                          (implicit context: PacketContext): Action = {

        val hwDst = context.wcmatch.getEthernetDestination
        if (Ethernet.isBroadcast(hwDst)) {
            context.log.debug("Received an L2 broadcast packet.")
            return handleL2Broadcast(inPort)
        }

        if (hwDst != inPort.portMac) { // Not addressed to us, log.warn and drop
            context.log.warn("{} neither broadcast nor inPort's MAC ({})",
                             hwDst, inPort.portMac)
            return DropAction
        }

        context.addFlowTag(deviceTag)
        handleNeighbouring(inPort) match {
            case Some(a: Action) => return a
            case None =>
        }

        context.outPortId = null // input port should be set already

        val preRoutingAction = applyServicesInbound() match {
            case res if res.action == RuleResult.Action.CONTINUE =>
                // Continue to inFilter / ingress chain

                val inFilter = if (cfg.inboundFilter == null) null
                               else tryAsk[Chain](cfg.inboundFilter)
                handlePreRoutingResult(
                    Chain.apply(inFilter, context, id, false), inPort
                )
            case res => // Skip the inFilter / ingress chain
                handlePreRoutingResult(res, inPort)
        }

        preRoutingAction match {
            case Some(a) => a
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
    protected def applyServicesInbound()(implicit context: PacketContext)
    : RuleResult = {
        if (cfg.loadBalancer == null)
            new RuleResult(RuleResult.Action.CONTINUE, null, context.wcmatch)
        else
            tryAsk[LoadBalancer](cfg.loadBalancer).processInbound(context)
    }


    private def routing(inPort: RouterPort)
                       (implicit context: PacketContext): Action = {

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

        def applyRoutingTable: (Route, Action) = {
            val rt: Route = routeBalancer.lookup(wcmatch, context.log)

            if (rt == null) {
                // No route to network
                context.log.debug(s"No route to network (dst:$dstIP)")
                sendAnswer(inPort.id,
                    icmpErrors.unreachableNetIcmp(inPort, wcmatch, frame))
                return (rt, DropAction)
            }

            val action = rt.nextHop match {
                case Route.NextHop.LOCAL if isIcmpEchoRequest(wcmatch) =>
                    context.log.debug("Got ICMP echo req, will reply")
                    sendIcmpEchoReply(wcmatch, frame)
                    ConsumedAction

                case Route.NextHop.LOCAL =>
                    context.log.debug("Dropping non icmp_req addressed to local port")
                    TemporaryDropAction

                case Route.NextHop.BLACKHOLE =>
                    context.log.debug("Dropping packet, BLACKHOLE route (dst:{})",
                        wcmatch.getNetworkDestinationIP)
                    TemporaryDropAction

                case Route.NextHop.REJECT =>
                    sendAnswer(inPort.id, icmpErrors.unreachableProhibitedIcmp(
                        inPort, wcmatch, frame))
                    context.log.debug("Dropping packet, REJECT route (dst:{})",
                        wcmatch.getNetworkDestinationIP)
                    DropAction

                case Route.NextHop.PORT if rt.nextHopPort == null =>
                    context.log.error(
                        "Routing table lookup for {} forwarded to port null.", dstIP)
                    // TODO(pino): should we remove this route?
                    DropAction

                case Route.NextHop.PORT =>
                    ToPortAction(rt.nextHopPort)

                case _ =>
                    context.log.warn(
                        "Routing table lookup for {} returned invalid nextHop of {}",
                        dstIP, rt.nextHop)
                    // rt.nextHop is invalid. The only way the simulation result
                    // would change is if there are other matching routes that are
                    // 'sane'. If such routes were created, this flow will be
                    // invalidated. Thus, we can return DropAction and not
                    // ErrorDropAction
                    DropAction
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
            case Some(a) => (null, a)
            case None => applyRoutingTable
        }

        applyTagsForRoute(rt, action)

        action match {
            case ToPortAction(outPortId) =>
                val outPort = tryAsk[RouterPort](outPortId)
                postRouting(inPort, outPort, rt, context)
            case a => action
        }
    }

    // POST ROUTING
    @throws[NotYetException]
    private def postRouting(inPort: RouterPort, outPort: RouterPort,
                            rt: Route, context: PacketContext): Action = {

        implicit val packetContext = context

        val pMatch = context.wcmatch
        val pFrame = context.ethernet

        context.outPortId = outPort.id

        val postRoutingResult = applyServicesOutbound() match {
            case res if res.action == RuleResult.Action.CONTINUE =>
                // Continue to outFilter / egress chain
                val outFilter = if (cfg.outboundFilter == null) null
                                else tryAsk[Chain](cfg.outboundFilter)
                Chain.apply(outFilter, context, id, false)
            case res => res // Skip outFilter / egress chain
        }

        postRoutingResult.action match {
            case RuleResult.Action.ACCEPT => // pass through
            case RuleResult.Action.DROP =>
                context.log.debug("PostRouting DROP rule")
                return DropAction
            case RuleResult.Action.REJECT =>
                context.log.debug("PostRouting REJECT rule")
                sendAnswer(inPort.id, icmpErrors.unreachableProhibitedIcmp(
                    inPort, pMatch, pFrame))
                return DropAction
            case other =>
                context.log.warn(
                    "PostRouting returned {} which was not ACCEPT, DROP or REJECT",
                    other)
                return DropAction
        }

        if (postRoutingResult.pmatch ne pMatch) {
            context.log.warn("PostRouting for returned a different match obj")
            return ErrorDropAction
        }
        if (pMatch.getNetworkDestinationIP == outPort.portAddr.getAddress) {
            context.log.warn("Got a packet addressed to a port without a LOCAL route")
            return DropAction
        }

        getNextHopMac(outPort, rt,
                      pMatch.getNetworkDestinationIP.asInstanceOf[IP]) match {
            case null if rt.nextHopGateway == 0 || rt.nextHopGateway == -1 =>
                context.log.debug("icmp host unreachable, host mac unknown")
                sendAnswer(inPort.id, icmpErrors.unreachableHostIcmp(
                    inPort, pMatch, pFrame))
                ErrorDropAction
            case null =>
                context.log.debug("icmp net unreachable, gw mac unknown")
                sendAnswer(inPort.id, icmpErrors.unreachableNetIcmp(
                    inPort, pMatch, pFrame))
                ErrorDropAction
            case nextHopMac =>
                context.log.debug("routing packet to {}", nextHopMac)
                pMatch.setEthernetSource(outPort.portMac)
                pMatch.setEthernetDestination(nextHopMac)
                new ToPortAction(rt.nextHopPort)
        }

    }

    def sendAnswer(portId: UUID, eth: Option[Ethernet])
                      (implicit context: PacketContext) {
        if (eth.nonEmpty)
            PacketsEntryPoint !
                EmitGeneratedPacket(portId, eth.get, context.flowCookie)
    }

    /**
     * This method will be executed after outbound filter rules are applied.
     *
     * Currently just does load balancing.
     */
    @throws[NotYetException]
    protected def applyServicesOutbound()(implicit context: PacketContext)
    : RuleResult =
        if (cfg.loadBalancer == null) {
            new RuleResult(RuleResult.Action.CONTINUE, null, context.wcmatch)
        } else {
            tryAsk[LoadBalancer](cfg.loadBalancer).processOutbound(context)
        }

    // Auxiliary, IP version specific abstract methods.

    /**
     * Given a route and a destination address, return the MAC address of
     * the next hop (or the destination's if it's a link-local destination)
     *
     * @param rt Route that the packet will be sent through
     * @param ipDest Final destination of the packet to be sent
     * @return
     */
    protected def getNextHopMac(outPort: RouterPort, rt: Route, ipDest: IP)
                               (implicit context: PacketContext): MAC

    /**
     * Will be called to construct an ICMP echo reply for an ICMP echo reply
     * contained in the given packet. Returns a Ready telling whether the reply
     * was finally sent or not, or a NotYet if some required data was not
     * locally cached.
     */
    protected def sendIcmpEchoReply(ingressMatch: WildcardMatch, packet: Ethernet)
                                   (implicit pktCtx: PacketContext): Boolean

    /**
     * Will be called from the pre-routing process immediately after receiving
     * the frame, if Ethernet.isBroadcast(hwDst).
     */
    protected def handleL2Broadcast(inPort: RouterPort)
                                   (implicit pktCtx: PacketContext): Action

    /**
     * This method will be executed after basic L2 processing is done,
     * including handling broadcasts and reacting to frames not addressed to
     * our MAC.
     */
    protected def handleNeighbouring(inPort: RouterPort)
                                    (implicit pktCtx: PacketContext): Option[Action]

    protected def isIcmpEchoRequest(mmatch: WildcardMatch): Boolean
}
