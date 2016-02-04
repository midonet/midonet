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

import java.nio.ByteBuffer
import java.util.UUID

import org.midonet.midolman.NotYetException
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.routingprotocols.RoutingWorkflow
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.Icmp._
import org.midonet.midolman.simulation.Router.{Config, RoutingTable, TagManager}
import org.midonet.midolman.topology.VirtualTopology.{VirtualDevice, tryGet}
import org.midonet.odp.FlowMatch
import org.midonet.odp.FlowMatch.Field
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger

object RouterBase {
    private val encapMac = MAC.fromString("02:00:00:00:00:00")
}

/**
 * Defines the base Router device that is meant to be extended with specific
 * implementations for IPv4 and IPv6 that deal with version specific details
 * such as ARP vs. NDP.
 */
abstract class RouterBase[IP <: IPAddr]()
                                       (implicit icmpErrors: IcmpErrorSender[IP])
    extends SimDevice with ForwardingDevice with InAndOutFilters
        with MirroringDevice with RoutingWorkflow with VirtualDevice {

    import org.midonet.midolman.simulation.Simulator._

    def isValidEthertype(ether: Short): Boolean

    val id: UUID
    val cfg: Config
    val rTable: RoutingTable
    val routerMgrTagger: TagManager
    val vniToPort: java.util.Map[Int, UUID]

    val routeBalancer = new RouteBalancer(rTable)
    override val deviceTag = FlowTagger.tagForRouter(id)
    override def infilters = cfg.inboundFilters
    override def outfilters = cfg.outboundFilters
    override def adminStateUp = cfg.adminStateUp

    private val routeAndMirrorOut: ContinueWith = ContinueWith(context => {
        preRouting()(context) match {
            case toPort: ToPortAction => mirroringOutbound(context, toPort)
            case action => action
        }
    })

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
                tryGet[RouterPort](context.inPortId) match {
                    case inPort if !cfg.adminStateUp =>
                        context.log.debug("Router {} state is down, DROP", id)
                        sendAnswer(inPort.id,
                            icmpErrors.unreachableProhibitedIcmp(inPort, context))
                        context.addFlowTag(deviceTag)
                        Drop
                    case inPort =>
                        mirroringInbound(context, routeAndMirrorOut)
                }
            }
        }
    }

    override val dropIn: DropHook = (context, action) => {
        implicit val c: PacketContext = context
        if (action == RuleResult.Action.DROP) {
            if (context.wcmatch.getIpFragmentType == IPFragmentType.First) {
                sendAnswer(context.inPortId,
                    icmpErrors.unreachableFragNeededIcmp(
                        tryGet[RouterPort](context.inPortId), context))
                ErrorDrop
            } else {
                Drop
            }
        } else {
            Drop
        }
    }

    @throws[NotYetException]
    private def preRouting()(implicit context: PacketContext): SimulationResult = {
        val inPort = tryGet[RouterPort](context.inPortId)
        val fmatch = context.wcmatch

        val hwDst = fmatch.getEthDst
        if (Ethernet.isBroadcast(hwDst)) {
            context.log.debug("Received an L2 broadcast packet.")
            return handleL2Broadcast(inPort)
        }

        context.addFlowTag(deviceTag)

        if (inPort.isL2) {
            if (!encapPacket(inPort, context))
                return Drop
        } else {
            if (isOverlayVtepPacket(inPort, fmatch)) {
                val outport = decapPacket(inPort, context)
                if (outport ne null)
                    return continue(context, outport.action)
            }

            if (hwDst != inPort.portMac) { // Not addressed to us, log.warn and drop
                context.log.warn("{} neither broadcast nor inPort's MAC ({})",
                                 hwDst, inPort.portMac)
                return Drop
            }
        }

        handleNeighbouring(inPort) match {
            case None =>
            case Some(simRes) => return simRes
        }

        context.outPortId = null // input port should be set already

        applyServicesInbound() match {
            case res if res.action == RuleResult.Action.CONTINUE =>
                // Continue to inFilter / ingress chain
                filterIn(context, continueIn)
            case res if res.action == RuleResult.Action.DROP =>
                // Skip the inFilter / ingress chain
                dropIn(context, res.action)
            case _ =>
                continueIn(context)
        }
    }

    private def encapPacket(inPort: RouterPort, context: PacketContext): Boolean = {
        val mac = context.wcmatch.getEthDst
        val remoteVtep = inPort.peeringTable.getLocal(mac)

        if (remoteVtep eq null) {
            context.log.debug(s"Could not find a remote VTEP for $mac")
            return false
        }

        def getSrcPort(ipBytes: Array[Byte], src: Int, dst: Int): Int = {
            val ipHigh = (ipBytes(0) << 8) | ipBytes(1)
            val ipLow = (ipBytes(2) << 8) | ipBytes(3)
            ((31 * ipHigh + ipLow) ^ (31 * src + dst)) & 0xFFFF
        }

        /*
         * We need to create a src port for the UDP packet that is as unique
         * as possible, but fit it into just 16 bytes. We pick the field that
         * are most likely to change per flow.
         */
        val newSrcPort = getSrcPort(context.wcmatch.getNetworkSrcIP.toBytes,
                                    context.wcmatch.getSrcPort,
                                    context.wcmatch.getDstPort)

        context.encap(
            vni = inPort.vni,
            srcMac = RouterBase.encapMac,
            dstMac = RouterBase.encapMac,
            srcIp = inPort.tunnelIp,
            dstIp = remoteVtep,
            tos = context.wcmatch.getNetworkTOS,
            ttl = -1,
            srcPort = newSrcPort,
            dstPort = UDP.VXLAN)

        context.log.debug(s"Encapsulated packet ${context.wcmatch}")
        true
    }

    private def isOverlayVtepPacket(inPort: RouterPort, fmatch: FlowMatch) =
        fmatch.getNetworkProto == UDP.PROTOCOL_NUMBER &&
        fmatch.getDstPort == UDP.VXLAN

    private def decapPacket(inPort: RouterPort, context: PacketContext): RouterPort = {
        val udpPayload = context.ethernet.getPayload.getPayload.getPayload
        val vxlan = udpPayload match {
            case vxlan: VXLAN =>
                vxlan
            case data: Data => // In case the dst port is not the VXLAN default
                val vxlan = new VXLAN()
                vxlan.deserialize(ByteBuffer.wrap(data.getData))
                vxlan
            case _ =>
                context.log.warn("UDP packet to VXLAN port has " +
                                 "unparseable payload.")
                return null
            }

        val vni = vxlan.getVni
        val l2port = vniToPort.get(vni)
        if (l2port eq null)
            return null

        val outPort = tryGet[RouterPort](l2port)
        if (outPort.tunnelIp != context.wcmatch.getNetworkDstIP)
            return null

        context.log.debug(s"Processing vxlan packet with vni=$vni " +
                          s"and udpSrc=${context.wcmatch.getSrcPort}")
        context.decap(
            inner = vxlan.getPayload.asInstanceOf[Ethernet],
            vni = vni)
        context.log.debug(s"Decapsulated packet ${context.wcmatch}")
        outPort
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
            new RuleResult(RuleResult.Action.CONTINUE)
        else
            tryGet[LoadBalancer](cfg.loadBalancer).processInbound(context)
    }


    private val continueIn: SimStep = context => {
        implicit val c: PacketContext = context
        val fmatch = context.wcmatch
        val dstIP = context.wcmatch.getNetworkDstIP
        val inPort = tryGet[RouterPort](context.inPortId)

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
                        case NoOp => tryGet[Port](rt.nextHopPort).action
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
                val outPort = tryGet[RouterPort](outPortId)
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

        context.outPortId = outPort.id
        context.routeTo = rt

        applyServicesOutbound() match {
            case res if res.action == RuleResult.Action.CONTINUE =>
                // Continue to outFilter / egress chain
                filterOut(context, continueOut)
            case res =>
                // Skip outFilter / egress chain
                continueOut(context)
        }
    }

    override protected def reject(context: PacketContext): Unit = {
        sendAnswer(context.inPortId,
            icmpErrors.unreachableProhibitedIcmp(
                tryGet[RouterPort](context.inPortId), context))(context)
    }

    private val continueOut: SimStep = context => {
        implicit val c: PacketContext = context
        val rt = context.routeTo
        context.routeTo = null

        val outPort = tryGet[RouterPort](context.outPortId)

        val mac = getNextHopMac(outPort, rt,
                                context.wcmatch.getNetworkDstIP.asInstanceOf[IP],
                                context)
        mac match {
            case null if rt.nextHopGateway == 0 || rt.nextHopGateway == -1 =>
                context.log.debug("icmp host unreachable, host mac unknown")
                sendAnswer(context.inPortId,
                    icmpErrors.unreachableHostIcmp(
                        tryGet[RouterPort](context.inPortId), context))
                ErrorDrop
            case null =>
                context.log.debug("icmp net unreachable, gw mac unknown")
                sendAnswer(context.inPortId,
                    icmpErrors.unreachableNetIcmp(
                        tryGet[RouterPort](context.inPortId), context))
                ErrorDrop
            case nextHopMac =>
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
            new RuleResult(RuleResult.Action.CONTINUE)
        } else {
            tryGet[LoadBalancer](cfg.loadBalancer).processOutbound(context)
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
