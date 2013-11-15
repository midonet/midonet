/*
 * Copyright 2013 Midokura Europe SARL
 */
package org.midonet.midolman.simulation

import java.util.UUID

import akka.dispatch.{Promise, Future, ExecutionContext}
import akka.actor.{ActorContext, ActorSystem}

import org.midonet.cluster.client.RouterPort
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology.{FlowTagger, RoutingTableWrapper, TagManager, RouterConfig}
import org.midonet.packets.{MAC, Unsigned, Ethernet, IPAddr}
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.midolman.simulation.Icmp._

/**
 * Defines the base Router device that is meant to be extended with specific
 * implementations for IPv4 and IPv6 that deal with version specific details
 * such as ARP vs. NDP.
 */
abstract class RouterBase[IP <: IPAddr]()(implicit context: ActorContext,
                                                   icmpErrors: IcmpErrorSender[IP])
    extends Coordinator.Device {

    import Coordinator._

    val id: UUID
    val cfg: RouterConfig
    val rTable: RoutingTableWrapper[IP]
    val inFilter: Chain
    val outFilter: Chain
    val routerMgrTagger: TagManager

    val validEthertypes: Set[Short]
    implicit val log = LoggerFactory.getSimulationAwareLog(
        this.getClass)(context.system.eventStream)

    val loadBalancer = new LoadBalancer(rTable)

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
     * @param ec         (implicit)
     * @param actorSystem (implicit)
     * @return An instance of Action that reflects what the device would do
     *         after handling this packet (e.g. drop it, consume it, forward it)
     */
    override def process(pktContext: PacketContext)
                        (implicit ec: ExecutionContext,
                                  actorSystem: ActorSystem): Future[Action] = {
        implicit val packetContext = pktContext

        if (!pktContext.wcmatch.getVlanIds.isEmpty) {
            log.info("Dropping VLAN tagged traffic")
            return Promise.successful(DropAction)
        }

        if (!validEthertypes.contains(pktContext.wcmatch.getEtherType)) {
            log.info("Dropping unsupported EtherType {}",
                      pktContext.wcmatch.getEtherType)
            return Promise.successful(unsupportedPacketAction)
        }

        getRouterPort(pktContext.getInPortId, pktContext.getExpiry) flatMap {
            case null => log.debug("Router - in port {} was null",
                                    pktContext.getInPortId())
                Promise.successful(DropAction)
            case inPort if !cfg.adminStateUp =>
                log.debug("Router {} state is down, DROP", id)

                icmpErrors.sendUnreachableProhibitedIcmp(
                    inPort,
                    pktContext.wcmatch,
                    pktContext.getFrame)
                Promise.successful(DropAction)
            case inPort => preRouting(inPort)
        }
    }

    private def applyIngressChain(inPort: RouterPort)
                                 (implicit ec: ExecutionContext,
                                  pktContext: PacketContext,
                                  actorSystem: ActorSystem): Option[Action] = {

        // Apply the pre-routing (ingress) chain
        pktContext.setOutputPort(null) // input port should be set already
        val preRoutingResult = Chain.apply(inFilter, pktContext,
                pktContext.wcmatch, id, false)

        preRoutingResult.action match {
            case RuleResult.Action.ACCEPT => // pass through
            case RuleResult.Action.DROP =>
                return Some(DropAction)
            case RuleResult.Action.REJECT =>
                icmpErrors.sendUnreachableProhibitedIcmp(inPort,
                    pktContext.wcmatch,
                    pktContext.getFrame)
                return Some(DropAction)
            case other =>
                log.error("Pre-routing for {} returned an action which was {}, " +
                    "not ACCEPT, DROP, or REJECT.", id, preRoutingResult.action)
                return Some(ErrorDropAction)
        }

        if (preRoutingResult.pmatch ne pktContext.wcmatch) {
            log.error("Pre-routing for {} returned a different match obj.", id)
            Some(ErrorDropAction)
        }
        None
    }


    private def preRouting(inPort: RouterPort)
                          (implicit pktContext: PacketContext,
                                    ec: ExecutionContext,
                                    actorSystem: ActorSystem): Future[Action] ={

        val hwDst = pktContext.wcmatch.getEthernetDestination
        if (Ethernet.isBroadcast(hwDst)) {
            log.debug("Received an L2 broadcast packet.")
            return Promise.successful(handleL2Broadcast(inPort))
        }

        if (hwDst != inPort.portMac) { // Not addressed to us, log.warn and drop
            log.warning("{} neither broadcast nor inPort's MAC ({})", hwDst,
                inPort.portMac)
            return Promise.successful(DropAction)
        }

        pktContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(id))
        handleNeighbouring(inPort) match {
            case Some(a: Action) => return Promise.successful(a)
            case None =>
        }

        applyIngressChain(inPort) match {
            case Some(a: Action) => return Promise.successful(a)
            case None =>
        }

        routing(inPort)

    }

    private def routing(inPort: RouterPort)
                       (implicit ec: ExecutionContext,
                                 pktContext: PacketContext,
                                 actorSystem: ActorSystem): Future[Action] = {

        val pMatch = pktContext.wcmatch
        val pFrame = pktContext.getFrame

        /* TODO(D-release): Have WildcardMatch take a DecTTLBy instead,
         * so that there need only be one sim. run for different TTLs.  */
        if (pMatch.getNetworkTTL != null) {
            val ttl = Unsigned.unsign(pMatch.getNetworkTTL)
            if (ttl <= 1) {
                icmpErrors.sendTimeExceededIcmp(inPort, pMatch, pFrame)
                return Promise.successful(DropAction)
            } else {
                pMatch.setNetworkTTL((ttl - 1).toByte)
            }
        }

        // tag using the destination IP
        val dstIP = pMatch.getNetworkDestinationIP
        pktContext.addFlowTag(FlowTagger.invalidateByIp(id, dstIP))
        // pass tag to the RouterManager so it'll be able to invalidate the flow
        routerMgrTagger.addTag(dstIP)
        // register the tag removal callback
        pktContext.addFlowRemovedCallback(routerMgrTagger
                                          .getFlowRemovalCallback(dstIP))

        val rt: Route = loadBalancer.lookup(pMatch)
        if (rt == null) {
            // No route to network
            log.debug("Route lookup: No route to network (dst:{}), {}",
                dstIP, rTable.rTable)
            icmpErrors.sendUnreachableNetIcmp(inPort, pMatch, pFrame)
            return Promise.successful(DropAction)
        }

        // tag using this route
        pktContext.addFlowTag(FlowTagger.invalidateByRoute(id, rt.hashCode()))

        rt.nextHop match {
            case Route.NextHop.LOCAL =>
                if (isIcmpEchoRequest(pMatch)) {
                    log.debug("got ICMP echo")
                    sendIcmpEchoReply(pMatch, pFrame, pktContext.getExpiry)
                    Promise.successful(ConsumedAction)
                } else {
                    Promise.successful(DropAction)
                }

            case Route.NextHop.BLACKHOLE =>
                log.debug("Dropping packet, BLACKHOLE route (dst:{})",
                    pMatch.getNetworkDestinationIP)
                Promise.successful(DropAction)

            case Route.NextHop.REJECT =>
                icmpErrors.sendUnreachableProhibitedIcmp(inPort, pMatch, pFrame)
                log.debug("Dropping packet, REJECT route (dst:{})",
                    pMatch.getNetworkDestinationIP)
                Promise.successful(DropAction)

            case Route.NextHop.PORT =>
                if (rt.nextHopPort == null) {
                    log.error("Routing table lookup for {} forwarded to port " +
                        "null.", dstIP)
                    // TODO(pino): should we remove this route?
                    Promise.successful(DropAction)
                } else {
                    getRouterPort(rt.nextHopPort, pktContext.getExpiry) flatMap {
                        case null =>
                            Promise.successful(ErrorDropAction)
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
                Promise.successful(DropAction)
        }

    }

    // POST ROUTING
    private def postRouting(inPort: RouterPort, outPort: RouterPort,
                            rt: Route, pktContext: PacketContext)
                           (implicit ec: ExecutionContext,
                            actorSystem: ActorSystem): Future[Action] = {

        implicit val packetContext = pktContext

        val pMatch = pktContext.wcmatch
        val pFrame = pktContext.getFrame

        pktContext.setOutputPort(outPort.id)
        val postRoutingResult =
            Chain.apply(outFilter, pktContext, pMatch, id, false)

        postRoutingResult.action match {
            case RuleResult.Action.ACCEPT => // pass through
            case RuleResult.Action.DROP =>
                log.debug("PostRouting DROP rule")
                return Promise.successful(DropAction)
            case RuleResult.Action.REJECT =>
                log.debug("PostRouting REJECT rule")
                icmpErrors.sendUnreachableProhibitedIcmp(inPort, pMatch, pFrame)
                return Promise.successful(DropAction)
            case other =>
                log.error("Post-routing for {} returned {} which was not " +
                    "ACCEPT, DROP or REJECT.", id, other)
                return Promise.successful(ErrorDropAction)
        }

        if (postRoutingResult.pmatch ne pMatch) {
            log.error("Post-routing for {} returned a different match obj.", id)
            return Promise.successful(ErrorDropAction)
        }

        if (pMatch.getNetworkDestinationIP == outPort.portAddr.getAddress) {
            log.error("Got a packet addressed to a port without a LOCAL route")
            return Promise.successful(DropAction)
        }

        // Set HWDst
        val macFuture = getNextHopMac(outPort, rt,
                              pMatch.getNetworkDestinationIP.asInstanceOf[IP],
                              pktContext.getExpiry)

        macFuture map {
            case null =>
                if (rt.nextHopGateway == 0 || rt.nextHopGateway == -1) {
                    log.debug("icmp host unreachable, host mac unknown")
                    icmpErrors.sendUnreachableHostIcmp(inPort, pMatch, pFrame)
                } else {
                    log.debug("icmp net unreachable, gw mac unknown")
                    icmpErrors.sendUnreachableNetIcmp(inPort, pMatch, pFrame)
                }
                ErrorDropAction
            case nextHopMac =>
                log.debug("routing packet to {}", nextHopMac)
                pMatch.setEthernetSource(outPort.portMac)
                pMatch.setEthernetDestination(nextHopMac)
                new ToPortAction(rt.nextHopPort)
        }

    }

    final protected def getRouterPort(portID: UUID, expiry: Long)
                                     (implicit actorSystem: ActorSystem,
                                      pktContext: PacketContext):
                                     Future[RouterPort] =
        expiringAsk(PortRequest(portID), log, expiry)
            .mapTo[RouterPort]

    // Auxiliary, IP version specific abstract methods.

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
    protected def getNextHopMac(outPort: RouterPort, rt: Route,
                                         ipDest: IP, expiry: Long)
                                        (implicit ec: ExecutionContext,
                                         actorSystem: ActorSystem,
                                         pktContext: PacketContext): Future[MAC]

    /**
     * Will be called to construct an ICMP echo reply for an ICMP echo reply
     * contained in the given packet.
     */
    protected def sendIcmpEchoReply(ingressMatch: WildcardMatch,
                                             packet: Ethernet, expiry: Long)
                                            (implicit ec: ExecutionContext,
                                             actorSystem: ActorSystem,
                                             packetContext: PacketContext)

    /**
     * Will be called from the pre-routing process immediately after receiving
     * the frame, if Ethernet.isBroadcast(hwDst).
     */
    protected def handleL2Broadcast(inPort: RouterPort)
                                   (implicit pktContext: PacketContext,
                                    ec: ExecutionContext,
                                    actorSystem: ActorSystem): Action

    /**
     * This method will be executed after basic L2 processing is done,
     * including handling broadcasts and reacting to frames not addressed to
     * our MAC.
     */
    protected def handleNeighbouring(inPort: RouterPort)
                                 (implicit ec: ExecutionContext,
                                  pktContext: PacketContext,
                                  actorSystem: ActorSystem): Option[Action]

    protected def isIcmpEchoRequest(mmatch: WildcardMatch): Boolean
}