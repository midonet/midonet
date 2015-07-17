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

import akka.actor.ActorSystem

import org.midonet.midolman.NotYetException
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.routingprotocols.RoutingWorkflow
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.Icmp._
import org.midonet.midolman.simulation.Router.{Config, RoutingTable, TagManager}
import org.midonet.midolman.topology.VirtualTopology.{FilterableDevice, VirtualDevice}
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology.devices.RouterPort
import org.midonet.odp.FlowMatch
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp.flows.FlowActions
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger

/**
 * Defines the base Router device that is meant to be extended with specific
 * implementations for IPv4 and IPv6 that deal with version specific details
 * such as ARP vs. NDP.
 */
abstract class RouterBase[IP <: IPAddr](val id: UUID,
                                        val cfg: Config,
                                        val rTable: RoutingTable,
                                        val routerMgrTagger: TagManager)
                                       (implicit system: ActorSystem,
                                        icmpErrors: IcmpErrorSender[IP])
    extends Coordinator.Device
    with RoutingWorkflow
    with VirtualDevice
    with FilterableDevice {

    import org.midonet.midolman.simulation.Coordinator._

    def isValidEthertype(ether: Short): Boolean

    val routeBalancer = new RouteBalancer(rTable)
    override val deviceTag = FlowTagger.tagForRouter(id)

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
    override def process(context: PacketContext): SimulationResult = {
        implicit val packetContext = context

        if (context.wcmatch.isVlanTagged) {
            context.log.debug("Dropping VLAN tagged traffic")
            return Drop
        }

        if (context.wcmatch.hasEthernetPcp) {
            context.log.debug("Stripping off VLAN 0 tag")
            context.addVirtualAction(FlowActions.popVLAN())
        }

        if (!isValidEthertype(context.wcmatch.getEtherType)) {
            context.log.debug(s"Dropping unsupported EtherType ${context.wcmatch.getEtherType}")
            return Drop
        }

        tryAsk[RouterPort](context.inPortId) match {
            case inPort if !cfg.adminStateUp =>
                context.log.debug("Router {} state is down, DROP", id)
                sendAnswer(inPort.id,
                           icmpErrors.unreachableProhibitedIcmp(inPort, context))
                context.addFlowTag(deviceTag)
                Drop
            case inPort =>
                preRouting(inPort)
        }
    }

    private def handlePreRoutingResult(preRoutingResult: RuleResult,
                                       inPort: RouterPort)
                                      (implicit context: PacketContext)
    : SimulationResult =
        preRoutingResult.action match {
            case RuleResult.Action.ACCEPT => NoOp // pass through
            case RuleResult.Action.DROP =>
                // For header fragments only, we should send an ICMP_FRAG_NEEDED
                // response and install a temporary drop flow so that the sender
                // will continue to receive these responses occasionally. We can
                // silently drop nonheader fragments and unfragmented packets.
                if (context.wcmatch.getIpFragmentType != IPFragmentType.First) {
                    Drop
                } else {
                    sendAnswer(inPort.id,
                               icmpErrors.unreachableFragNeededIcmp(inPort, context))
                    ErrorDrop
                }
            case RuleResult.Action.REJECT =>
                sendAnswer(inPort.id,
                           icmpErrors.unreachableProhibitedIcmp(inPort, context))
                Drop
            case _ =>
                context.log.warn("Pre-routing returned an action which was {}, " +
                                 "not ACCEPT, DROP, or REJECT.", preRoutingResult.action)
                ErrorDrop
        }

    @throws[NotYetException]
    private def preRouting(inPort: RouterPort)
                          (implicit context: PacketContext): SimulationResult = {

        val hwDst = context.wcmatch.getEthDst
        if (Ethernet.isBroadcast(hwDst)) {
            context.log.debug("Received an L2 broadcast packet.")
            return handleL2Broadcast(inPort)
        }

        if (hwDst != inPort.portMac) { // Not addressed to us, log.warn and drop
            context.log.warn("{} neither broadcast nor inPort's MAC ({})",
                             hwDst, inPort.portMac)
            return Drop
        }

        context.addFlowTag(deviceTag)
        handleNeighbouring(inPort) match {
            case None =>
            case Some(simRes) => return simRes
        }

        context.outPortId = null // input port should be set already

        val preRoutingAction = applyServicesInbound() match {
            case res if res.action == RuleResult.Action.CONTINUE =>
                // Continue to inFilter / ingress chain

                val inFilter = if (cfg.inboundFilter == null) null
                               else tryAsk[Chain](cfg.inboundFilter)
                handlePreRoutingResult(
                    Chain.apply(inFilter, context, id, false, checkpointAction),
                    inPort)
            case res => // Skip the inFilter / ingress chain
                handlePreRoutingResult(res, inPort)
        }

        preRoutingAction match {
            case NoOp => routing(inPort)
            case simRes => simRes
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
            new RuleResult(RuleResult.Action.CONTINUE, null)
        else
            tryAsk[LoadBalancer](cfg.loadBalancer).processInbound(context)
    }


    private def routing(inPort: RouterPort)
                       (implicit context: PacketContext): SimulationResult = {
        val fmatch = context.wcmatch
        val dstIP = context.wcmatch.getNetworkDstIP

        def applyTimeToLive(): SimulationResult = {
            if (fmatch.isUsed(Field.NetworkTTL)) {
                val ttl = Unsigned.unsign(fmatch.getNetworkTTL)
                if (ttl <= 1) {
                    sendAnswer(inPort.id,
                               icmpErrors.timeExceededIcmp(inPort, context))
                    ErrorDrop
                } else {
                    context.wcmatch.setNetworkTTL((ttl - 1).toByte)
                    NoOp
                }
            } else {
                NoOp
            }
        }

        def applyRoutingTable(): (Route, SimulationResult) = {
            val rt: Route = routeBalancer.lookup(fmatch, context.log)

            if (rt == null) {
                // No route to network
                context.log.debug(s"No route to network (dst:$dstIP)")
                sendAnswer(inPort.id,
                           icmpErrors.unreachableNetIcmp(inPort, context))
                return (rt, ShortDrop)
            }

            val action = rt.nextHop match {
                case Route.NextHop.LOCAL if isIcmpEchoRequest(fmatch) =>
                    context.log.debug("Got ICMP echo req, will reply")
                    sendIcmpEchoReply(context)
                    NoOp

                case Route.NextHop.LOCAL =>
                    handleBgp(context, inPort) match {
                        case NoOp =>
                            context.log.debug("Dropping non icmp_req addressed to local port")
                            ErrorDrop
                        case simRes =>
                            context.log.debug("Matched BGP traffic")
                            simRes
                    }

                case Route.NextHop.BLACKHOLE =>
                    context.log.debug("Dropping packet, BLACKHOLE route (dst:{})",
                        fmatch.getNetworkDstIP)
                    ErrorDrop

                case Route.NextHop.REJECT =>
                    sendAnswer(inPort.id,
                               icmpErrors.unreachableProhibitedIcmp(inPort, context))
                    context.log.debug("Dropping packet, REJECT route (dst:{})",
                        fmatch.getNetworkDstIP)
                    ShortDrop

                case Route.NextHop.PORT if rt.nextHopPort == null =>
                    context.log.error(
                        "Routing table lookup for {} forwarded to port null.", dstIP)
                    ErrorDrop

                case Route.NextHop.PORT =>
                    applyTimeToLive() match {
                        case NoOp => ToPortAction(rt.nextHopPort)
                        case simRes => simRes
                    }
                case _ =>
                    context.log.warn(
                        "Routing table lookup for {} returned invalid nextHop of {}",
                        dstIP, rt.nextHop)
                    ShortDrop
            }

            (rt, action)
        }

        val (rt, action) = applyRoutingTable()

        applyTagsForRoute(rt, action)

        action match {
            case ToPortAction(outPortId) =>
                val outPort = tryAsk[RouterPort](outPortId)
                postRouting(inPort, outPort, rt, context)
            case _ => action
        }
    }

    protected def applyTagsForRoute(route: Route,
        simRes: SimulationResult)(implicit context: PacketContext): Unit

    // POST ROUTING
    @throws[NotYetException]
    private def postRouting(inPort: RouterPort, outPort: RouterPort,
                            rt: Route, context: PacketContext): SimulationResult = {

        implicit val packetContext = context

        val pMatch = context.wcmatch
        val pFrame = context.ethernet

        context.outPortId = outPort.id

        val postRoutingResult = applyServicesOutbound() match {
            case res if res.action == RuleResult.Action.CONTINUE =>
                // Continue to outFilter / egress chain
                val outFilter = if (cfg.outboundFilter == null) null
                                else tryAsk[Chain](cfg.outboundFilter)
                Chain.apply(outFilter, context, id, false, checkpointAction)
            case res => res // Skip outFilter / egress chain
        }

        postRoutingResult.action match {
            case RuleResult.Action.ACCEPT => // pass through
            case RuleResult.Action.DROP =>
                context.log.debug("PostRouting DROP rule")
                return Drop
            case RuleResult.Action.REJECT =>
                context.log.debug("PostRouting REJECT rule")
                sendAnswer(inPort.id,
                           icmpErrors.unreachableProhibitedIcmp(inPort, context))
                return Drop
            case other =>
                context.log.warn(
                    "PostRouting returned {} which was not ACCEPT, DROP or REJECT",
                    other)
                return Drop
        }

        if (pMatch.getNetworkDstIP == outPort.portAddr.getAddress) {
            context.log.warn("Got a packet addressed to a port without a LOCAL route")
            return Drop
        }

        getNextHopMac(outPort, rt,
                      pMatch.getNetworkDstIP.asInstanceOf[IP], context) match {
            case null if rt.nextHopGateway == 0 || rt.nextHopGateway == -1 =>
                context.log.debug("icmp host unreachable, host mac unknown")
                sendAnswer(inPort.id,
                           icmpErrors.unreachableHostIcmp(inPort, context))
                ErrorDrop
            case null =>
                context.log.debug("icmp net unreachable, gw mac unknown")
                sendAnswer(inPort.id,
                           icmpErrors.unreachableNetIcmp(inPort, context))
                ErrorDrop
            case nextHopMac =>
                context.log.debug("routing packet to {}", nextHopMac)
                pMatch.setEthSrc(outPort.portMac)
                pMatch.setEthDst(nextHopMac)
                new ToPortAction(rt.nextHopPort)
        }

    }

    def sendAnswer(portId: UUID, eth: Option[Ethernet])
                  (implicit context: PacketContext): Unit =
        if (eth.nonEmpty) {
            context.addGeneratedPacket(portId, eth.get)
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
            new RuleResult(RuleResult.Action.CONTINUE, null)
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
    protected def getNextHopMac(outPort: RouterPort, rt: Route,
                                ipDest: IP, context: PacketContext): MAC

    /**
     * Will be called to construct an ICMP echo reply for an ICMP echo reply
     * contained in the given packet.
     */
    protected def sendIcmpEchoReply(context: PacketContext): Boolean

    /**
     * Will be called from the pre-routing process immediately after receiving
     * the frame, if Ethernet.isBroadcast(hwDst).
     */
    protected def handleL2Broadcast(inPort: RouterPort)
                                   (implicit pktCtx: PacketContext): SimulationResult

    /**
     * This method will be executed after basic L2 processing is done,
     * including handling broadcasts and reacting to frames not addressed to
     * our MAC.
     */
    protected def handleNeighbouring(inPort: RouterPort)
                                    (implicit pktCtx: PacketContext): Option[SimulationResult]

    protected def isIcmpEchoRequest(mmatch: FlowMatch): Boolean
}
