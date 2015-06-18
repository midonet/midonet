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

import scala.collection.mutable
import scala.util.Random

import akka.actor.ActorSystem

import org.midonet.midolman.NotYetException
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.routingprotocols.RoutingWorkflow
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.Icmp._
import org.midonet.midolman.simulation.Router.{Config, RoutingTable, TagManager}
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.odp.{FlowMatches, Packet, FlowMatch}
import org.midonet.odp.FlowMatch.Field
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
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
    extends SimDevice with ForwardingDevice with InAndOutFilters
        with MirroringDevice with RoutingWorkflow with VirtualDevice {

    import org.midonet.midolman.simulation.Simulator._

    def isValidEthertype(ether: Short): Boolean

    val routeBalancer = new RouteBalancer(rTable)
    override val deviceTag = FlowTagger.tagForRouter(id)
    override def infilter = cfg.inboundFilter
    override def outfilter = cfg.outboundFilter
    override def adminStateUp = cfg.adminStateUp

    private def preRt(context: PacketContext, actorSystem: ActorSystem): SimulationResult = {
        implicit val packetContext = context
        val inPort = tryAsk[RouterPort](context.inPortId)
        context.addFlowTag(deviceTag)
        // If it's a L2/VTEP port, encap the packet, then pre-route.
        if (inPort.vni != 0)
            return encap(inPort)
        // If it's a vxlan packet and matches both the vni and local
        // VTEP ip of a L2 port, decap and emit from the L2 port.
        // TODO: make the UDP port configurable? Or per-L2 port?
        if (context.wcmatch.getNetworkProto == UDP.PROTOCOL_NUMBER
                 && context.wcmatch.getDstPort == UDP.VXLAN) {
            val vni = context.ethernet.getPayload.getPayload
                .getPayload.asInstanceOf[VXLAN].getVni
            // Since the kernel flow rules don't match any of the VXLAN
            // header, we read the UDP source port. This guarantees that
            // whatever treatment we give this packet (route or decap)
            // will apply to only flows with the same source port.
            val udpSrc = context.wcmatch.getSrcPort
            context.log.debug(s"Processing vxlan packet with vni=$vni" +
                              s"and udpSrc=$udpSrc")
            vniToL2Port.get(vni) match {
                case None => // drop through
                case Some(l2portId) =>
                    val l2port = tryAsk[RouterPort](l2portId)
                    if (l2port.localVtep == context.wcmatch.getNetworkDstIP)
                    //if (l2port.localVtep.equals(context.wcmatch.getNetworkDstIP)) {
                        return decapAndEmit(l2portId)
            }
            // else drop through
        }
        preRouting()(context) match {
            case toPort: ToPortAction => mirroringOutbound(context, toPort, system)
            case action => action
        }
    }

    private val routeAndMirrorOut: ContinueWith = ContinueWith(preRt)

    // This is populated by the RouterMapper
    val vniToL2Port = new mutable.HashMap[Int, UUID]

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
        context.currentDevice = id

        if (context.wcmatch.isVlanTagged) {
            context.log.debug("Dropping VLAN tagged traffic")
            Drop
        } else {
            if (context.wcmatch.stripEthernetPcp())
                context.log.debug("Stripping off VLAN 0 tag")

            if (!isValidEthertype(context.wcmatch.getEtherType)) {
                context.log.debug(s"Dropping unsupported EtherType ${context.wcmatch.getEtherType}")
                Drop
            } else {
                tryAsk[RouterPort](context.inPortId) match {
                    case inPort if !cfg.adminStateUp =>
                        context.log.debug("Router {} state is down, DROP", id)
                        sendAnswer(inPort.id,
                            icmpErrors.unreachableProhibitedIcmp(inPort, context))
                        context.addFlowTag(deviceTag)
                        Drop
                    case inPort =>
                        mirroringInbound(context, routeAndMirrorOut, system)
                }
            }
        }
    }

    override val dropIn: DropHook = (context, as, action) => {
        implicit val c: PacketContext = context
        if (action == RuleResult.Action.DROP) {
            if (context.wcmatch.getIpFragmentType == IPFragmentType.First) {
                sendAnswer(context.inPortId,
                    icmpErrors.unreachableFragNeededIcmp(
                        tryAsk[RouterPort](context.inPortId), context))
                ErrorDrop
            } else {
                Drop
            }
        } else {
            Drop
        }
    }

    /**
     * Process a packet that was recirculated.
     * @param context
     * @return
     */
    def recircEncap(context: PacketContext, l2port: RouterPort,
                    vtep: IPv4Addr): SimulationResult = {
        context.addFlowTag(deviceTag)
        context.wcmatch.setNetworkSrc(l2port.localVtep)
        context.wcmatch.setNetworkDst(vtep)
        context.wcmatch.setSrcPort(Random.nextInt() >>> 17)
        context.wcmatch.setDstPort(UDP.VXLAN.toShort)
        // TODO: verify we need to set this manually
        context.inPortId = l2port.id
        continue(context, preRouting()(context))
    }

    def recircDecap(context: PacketContext): SimulationResult = {
        context.addFlowTag(deviceTag)
        val vni = context.wcmatch.getTunnelKey.toInt
        vniToL2Port.get(vni) match {
            case None =>
                context.log.warn(s"Could not find a L2 port with vni=$vni")
                Drop
            case Some(portId) =>
                ToPortAction(portId)
        }
    }

    private def encap(fromL2Port: RouterPort)
                     (implicit context: PacketContext): SimulationResult = {
        // TODO: propagate inner flow's state to ingresses of encap's return
        // Find the remote VTEP appropriate for the dst MAC of the inner pkt.
        var remoteVtep = fromL2Port.remoteVteps.get(context.wcmatch.getEthDst) match {
            case None => fromL2Port.defaultRemoteVtep
            case Some(ip) => ip
        }
        if (remoteVtep eq null) {
            context.log.warn(s"Could not find a remote VTEP for mac=${context.wcmatch.getEthSrc}")
            return Drop
        }
        if (!fromL2Port.offRampVxlan) {
            // We will need to locally recirculate the packet to add the vxlan
            // header before emitting it.
            context.log.debug("Encap and recirculate packet that " +
                              "ingressed l2Port {}", fromL2Port.id)
            context.calculateActionsFromMatchDiff()
            context.addVirtualAction(
                VxlanEncap(fromL2Port.vni, fromL2Port.id, remoteVtep))
            return AddVirtualWildcardFlow
        }
        // Since vxlan encap can be off-ramped, we add the encapsulation
        // headers on the packet and continue the simulation.
        // TODO: choose the source port by hashing?
        val udpSrc = Random.nextInt() >>> 17
        val outerEthernet =  (eth addr "00:00:00:00:00:00" -> fromL2Port.portMac.toString)  <<
                             (ip4 src fromL2Port.localVtep dst remoteVtep) <<
                             (udp ports udpSrc ---> UDP.VXLAN.toShort) <<
                             (vxlan vni fromL2Port.vni) <<
                             payload(context.ethernet.serialize())
        val outerMatch = FlowMatches.fromEthernetPacket(outerEthernet)
        val outerPacket = new Packet(outerEthernet, outerMatch)
        // Return an error if the last step was Encap: we can't do two encaps
        // in a row.
        context.encap(outerPacket, fromL2Port.vni, fromL2Port.offRampVxlan) match {
            case false =>
                context.log.warn("Cannot add 2 layers of encapsulation")
                ErrorDrop
            case true =>
                // TODO: is this ok?
                context.inPortId = fromL2Port.id
                preRouting()
        }
    }

    private def decapAndEmit(outPortId: UUID)
                             (implicit context: PacketContext): SimulationResult =
        context.decap() match {
            case true => ToPortAction(outPortId)
            case false =>
                // If there was no previous encapsulation added by the network
                // simulation, then we return *Decap* as a result so that the
                // outer frame can be decapsulated by the hypervisor and the
                // inner frame is recirculated (and gets its own simulation).
                context.log.debug("Decap and recirculate packet so that it " +
                                  "can be emitted from l2port {}", outPortId)
                // We don't need to modify the outer headers since they're just
                // going to be removed.
                context.addVirtualAction(VxlanDecap(id))
                AddVirtualWildcardFlow
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
    private def preRouting()(implicit context: PacketContext): SimulationResult = {
        val inPort = tryAsk[RouterPort](context.inPortId)

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

        handleNeighbouring(inPort) match {
            case None =>
            case Some(simRes) => return simRes
        }

        context.outPortId = null // input port should be set already

        applyServicesInbound() match {
            case res if res.action == RuleResult.Action.CONTINUE =>
                // Continue to inFilter / ingress chain
                filterIn(context, system, continueIn)
            case res if res.action == Drop => // Skip the inFilter / ingress chain
                dropIn(context, system, res.action)
            case _ =>
                continueIn(context, system)
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


    private val continueIn: SimStep = (context, as) => {
        implicit val c: PacketContext = context
        val fmatch = context.wcmatch
        val dstIP = context.wcmatch.getNetworkDstIP
        val inPort = tryAsk[RouterPort](context.inPortId)

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
                        case NoOp => tryAsk[Port](rt.nextHopPort).action
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
                postRouting(outPort, rt, context)
            case _ => action
        }
    }

    protected def applyTagsForRoute(route: Route,
        simRes: SimulationResult)(implicit context: PacketContext): Unit

    @throws[NotYetException]
    private def postRouting(outPort: RouterPort, rt: Route,
                            context: PacketContext): SimulationResult = {

        implicit val packetContext = context

        if (context.wcmatch.getNetworkDstIP == outPort.portAddress) {
            context.log.warn("Got a packet addressed to a port without a LOCAL route")
            return Drop
        }

        val pMatch = context.wcmatch
        val pFrame = context.ethernet

        context.outPortId = outPort.id
        context.routeTo = rt

        applyServicesOutbound() match {
            case res if res.action == RuleResult.Action.CONTINUE =>
                // Continue to outFilter / egress chain
                filterOut(context, system, continueOut)
            case res =>
                // Skip outFilter / egress chain
                continueOut(context, system)
        }
    }

    override protected def reject(context: PacketContext, as: ActorSystem): Unit = {
        sendAnswer(context.inPortId,
            icmpErrors.unreachableProhibitedIcmp(
                tryAsk[RouterPort](context.inPortId), context))(context)
    }

    private val continueOut: SimStep = (context, as) => {
        implicit val c: PacketContext = context
        val rt = context.routeTo
        context.routeTo = null

        getNextHopMac(tryAsk[RouterPort](context.outPortId), rt,
            context.wcmatch.getNetworkDstIP.asInstanceOf[IP], context) match {
            case null if rt.nextHopGateway == 0 || rt.nextHopGateway == -1 =>
                context.log.debug("icmp host unreachable, host mac unknown")
                sendAnswer(context.inPortId,
                    icmpErrors.unreachableHostIcmp(
                        tryAsk[RouterPort](context.inPortId), context))
                ErrorDrop
            case null =>
                context.log.debug("icmp net unreachable, gw mac unknown")
                sendAnswer(context.inPortId,
                    icmpErrors.unreachableNetIcmp(
                        tryAsk[RouterPort](context.inPortId), context))
                ErrorDrop
            case nextHopMac =>
                val outPort = tryAsk[RouterPort](rt.nextHopPort)
                context.log.debug("routing packet to {}", nextHopMac)
                context.wcmatch.setEthSrc(outPort.portMac)
                context.wcmatch.setEthDst(nextHopMac)
                outPort.action
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
