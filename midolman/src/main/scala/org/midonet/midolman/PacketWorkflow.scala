/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.util.{List => JList}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.reflect.ClassTag

import akka.actor._

import org.midonet.cluster.DataClient
import org.midonet.cluster.client.Port
import org.midonet.midolman.DeduplicationActor.ActionsCache
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.simulation.{Coordinator, PacketContext, DhcpImpl}
import org.midonet.midolman.state.FlowStateReplicator
import org.midonet.midolman.topology.{VirtualTopologyActor, VirtualToPhysicalMapper, VxLanPortMapper}
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.sdn.flows.FlowTagger.tagForDpPort
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

trait PacketHandler {
    def start(context: PacketContext): Urgent[PacketWorkflow.PipelinePath]
    def drop(context: PacketContext): Unit
}

object PacketWorkflow {
    case class PacketIn(wMatch: WildcardMatch,
                        eth: Ethernet,
                        dpMatch: FlowMatch,
                        reason: Packet.Reason,
                        cookie: Int)

    val TEMPORARY_DROP_MILLIS = 5 * 1000
    val IDLE_EXPIRATION_MILLIS = 60 * 1000

    sealed trait SimulationResult

    case object NoOp extends SimulationResult

    case object Drop extends SimulationResult

    case object TemporaryDrop extends SimulationResult

    case class SendPacket(actions: List[FlowAction]) extends SimulationResult

    case class AddVirtualWildcardFlow(flow: WildcardFlow) extends SimulationResult

    sealed trait PipelinePath
    case object WildcardTableHit extends PipelinePath
    case object PacketToPortSet extends PipelinePath
    case object StateMessage extends PipelinePath
    case object Simulation extends PipelinePath
    case object Error extends PipelinePath
}

class PacketWorkflow(protected val dpState: DatapathState,
                     val datapath: Datapath,
                     val dataClient: DataClient,
                     val dpConnPool: DatapathConnectionPool,
                     val actionsCache: ActionsCache,
                     val replicator: FlowStateReplicator)
                    (implicit val system: ActorSystem)
        extends FlowTranslator with PacketHandler {

    import PacketWorkflow._
    import DeduplicationActor._
    import FlowController.{AddWildcardFlow, FlowAdded}
    import VirtualToPhysicalMapper.PortSetForTunnelKeyRequest

    val ERROR_CONDITION_HARD_EXPIRATION = 10000

    val resultLogger = Logger(LoggerFactory.getLogger("org.midonet.packets.results"))

    override def start(context: PacketContext): Urgent[PipelinePath] = {
        context.prepareForSimulation(FlowController.lastInvalidationEvent)
        context.log.debug("Initiating processing, attempt: {}",
                          context.runs.underlying())
        val res = if (context.ingressed)
                    handlePacketWithCookie(context)
                  else
                    doEgressPortSimulation(context)
        res
    }

    override def drop(context: PacketContext) {
        context.prepareForDrop(FlowController.lastInvalidationEvent)
        val wildFlow = WildcardFlow(wcmatch = context.origMatch,
            hardExpirationMillis = ERROR_CONDITION_HARD_EXPIRATION)
        addTranslatedFlow(context, wildFlow)
    }

    def logResultNewFlow(msg: String, context: PacketContext, wflow: WildcardFlow) {
        resultLogger.debug(s"$msg: match ${wflow.getMatch} will create flow with " +
            s"actions ${wflow.actions}, and tags ${context.flowTags}")
    }

    def logResultMatchedFlow(msg: String, context: PacketContext, wflow: WildcardFlow) {
        resultLogger.debug(s"$msg: match ${wflow.getMatch} with actions ${wflow.actions}")
    }

    def runSimulation(context: PacketContext): Urgent[SimulationResult] =
        new Coordinator(context).simulate()

    private def notifyFlowAdded(context: PacketContext,
                                flow: Flow,
                                newWildFlow: Option[WildcardFlow]) {
        context.log.debug("Successfully created flow")
        newWildFlow match {
            case None =>
                FlowController ! FlowAdded(flow, context.origMatch)
                addToActionsCacheAndInvalidate(context, flow.getActions)
            case Some(wf) =>
                FlowController ! AddWildcardFlow(wf, flow,
                                context.flowRemovedCallbacks, context.flowTags,
                                context.lastInvalidation, context.packet.getMatch,
                                actionsCache.pending,
                                actionsCache.getSlot())
                actionsCache.actions.put(context.packet.getMatch, flow.getActions)
        }
    }

    private def createFlow(context: PacketContext,
                           wildFlow: WildcardFlow,
                           newWildFlow: Option[WildcardFlow] = None) {
        context.log.debug("Creating flow from {}", wildFlow)

        val flowMatch = context.packet.getMatch
        val flowMask = new FlowMask()
        if (flowMatch.hasKey(OpenVSwitch.FlowKey.Attr.TcpFlags)) {
            // wildcard the TCP flags
            // TODO: this will change in the future: we'll use the wildcard match
            //       until then when we are smarter, we must set exact matches
            //       for everything up to the TCPFlags level... [alvaro]
            flowMask.addKey(FlowKeys.priority(FlowMask.PRIO_EXACT)).
                     addKey(FlowKeys.inPort(FlowMask.INPORT_EXACT)).
                     addKey(FlowKeys.ethernet(FlowMask.ETHER_EXACT,
                                              FlowMask.ETHER_EXACT)).
                     addKey(FlowKeys.etherType(FlowMask.ETHERTYPE_EXACT)).
                     addKey(FlowKeys.ipv4(FlowMask.IP_EXACT, FlowMask.IP_EXACT,
                            FlowMask.BYTE_EXACT, FlowMask.BYTE_EXACT,
                            FlowMask.BYTE_EXACT, FlowMask.BYTE_EXACT)).
                     addKey(FlowKeys.tcp(FlowMask.TCP_EXACT, FlowMask.TCP_EXACT)).
                     addKey(FlowKeys.tcpFlags(FlowMask.TCPFLAGS_ANY))
        }

        val dpFlow = new Flow(flowMatch, flowMask, wildFlow.getActions)
        try {
            datapathConn(context).flowsCreate(datapath, dpFlow)
            notifyFlowAdded(context, dpFlow, newWildFlow)
        } catch {
            case e: NetlinkException =>
                context.log.info("Failed to add flow packet", e)
                addToActionsCacheAndInvalidate(context, dpFlow.getActions)
        }
    }

    private def addTranslatedFlow(context: PacketContext,
                                  wildFlow: WildcardFlow): Unit = {
        if (context.packet.getReason == Packet.Reason.FlowActionUserspace) {
            resultLogger.debug("packet came up due to userspace dp action, " +
                               s"match ${wildFlow.getMatch}")
            context.runFlowRemovedCallbacks()
        } else {
            // ApplyState needs to happen before we add the wildcard flow
            // because it adds callbacks to the PacketContext and it can also
            // result in a NotYet exception being thrown.
            applyState(context, wildFlow.getActions)
            handleFlow(context, wildFlow)
        }

        executePacket(context, wildFlow.getActions)
    }

    private def handleFlow(context: PacketContext, wildFlow: WildcardFlow): Unit = {
        if (context.isGenerated) {
            context.log.warn(s"Tried to add a flow for a generated packet ${wildFlow.getMatch}")
            context.runFlowRemovedCallbacks()
        } else if (wildFlow.wcmatch.userspaceFieldsSeen) {
            logResultNewFlow("will create userspace flow", context, wildFlow)
            context.log.debug("Adding wildcard flow {} for match with userspace " +
                              "only fields, without a datapath flow", wildFlow)
            FlowController ! AddWildcardFlow(wildFlow, null, context.flowRemovedCallbacks,
                context.flowTags, context.lastInvalidation, context.packet.getMatch,
                actionsCache.pending, actionsCache.getSlot())
            actionsCache.actions.put(context.packet.getMatch, wildFlow.actions)
        } else {
            logResultNewFlow("will create flow", context, wildFlow)
            createFlow(context, wildFlow, Some(wildFlow))
        }
    }

    private def addTranslatedFlowForActions(
                            context: PacketContext,
                            actions: Seq[FlowAction],
                            hardExpirationMillis: Int = 0,
                            idleExpirationMillis: Int = IDLE_EXPIRATION_MILLIS) {

        val wildFlow = WildcardFlow(
            wcmatch = context.origMatch,
            hardExpirationMillis = hardExpirationMillis,
            idleExpirationMillis = idleExpirationMillis,
            actions =  actions.toList,
            priority = 0)

        addTranslatedFlow(context, wildFlow)
    }

    def executePacket(context: PacketContext, actions: Seq[FlowAction]): Unit = {
        if (actions.isEmpty) {
            context.log.debug("Dropping packet")
            return
        }

        try {
            context.log.debug("Executing packet")
            datapathConn(context).packetsExecute(datapath, context.packet, actions)
        } catch {
            case e: NetlinkException =>
                context.log.info("Failed to execute packet", e)
        }
    }

    def applyState(context: PacketContext, actions: Seq[FlowAction]): Unit =
        if (!actions.isEmpty) {
            context.log.debug("Applying connection state")
            val outPort = context.outPortId
            replicator.accumulateNewKeys(context.state.conntrackTx,
                                         context.state.natTx,
                                         context.inputPort,
                                         if (context.toPortSet) null else outPort,
                                         if (context.toPortSet) outPort else null,
                                         context.flowTags,
                                         context.flowRemovedCallbacks)
            replicator.pushState(datapathConn(context))
            context.state.conntrackTx.commit()
            context.state.natTx.commit()
    }

    private def handlePacketWithCookie(context: PacketContext)
    : Urgent[PipelinePath] = {
        if (context.origMatch.getInputPortNumber eq null) {
            context.log.error("packet had no inPort number")
            return Ready(Error)
        }

        context.flowTags.add(tagForDpPort(context.origMatch.getInputPortNumber.toShort))

        if (context.packet.getReason == Packet.Reason.FlowActionUserspace) {
            setVportForLocalTraffic(context)
            processSimulationResult(context, simulatePacketIn(context))
        } else {
            FlowController.queryWildcardFlowTable(context.origMatch) match {
                case Some(wildflow) =>
                    handleWildcardTableMatch(context, wildflow)
                    Ready(WildcardTableHit)
                case None =>
                    context.log.debug("missed the wildcard flow table")
                    handleWildcardTableMiss(context)
            }
        }
    }

    def handleWildcardTableMatch(context: PacketContext,
                                 wildFlow: WildcardFlow): Unit = {
        context.log.debug("matched a wildcard flow with actions {}", wildFlow.actions)
        if (wildFlow.wcmatch.userspaceFieldsSeen) {
            logResultMatchedFlow("matched a userspace flow", context, wildFlow)
            context.log.debug("no datapath flow for match {} with userspace only fields",
                      wildFlow.wcmatch)
            addToActionsCacheAndInvalidate(context, wildFlow.getActions)
        } else {
            logResultMatchedFlow("matched a wildcard flow", context, wildFlow)
            createFlow(context, wildFlow)
        }

        executePacket(context, wildFlow.getActions)
    }

    private def handleWildcardTableMiss(context: PacketContext): Urgent[PipelinePath] = {
        /** If the FlowMatch indicates that the packet came from a tunnel port,
         *  we assume here there are only two possible situations: 1) the tunnel
         *  port type is gre, in which case we are dealing with host2host
         *  tunnelled traffic; 2) the tunnel port type is vxlan, in which case
         *  we are dealing with vtep to midolman traffic. In the future, using
         *  vxlan encap for host2host tunnelling will break this assumption. */
        val wcMatch = context.origMatch
        if (wcMatch.isFromTunnel) {
            if (context.isStateMessage) {
                handleStateMessage(context)
            } else if (dpState isOverlayTunnellingPort wcMatch.getInputPortNumber) {
                handlePacketToPortSet(context)
            } else {
                val portIdOpt = VxLanPortMapper uuidOf wcMatch.getTunnelID.toInt
                context.inputPort = portIdOpt.orNull
                processSimulationResult(context, simulatePacketIn(context))
            }
        } else {
            setVportForLocalTraffic(context)
            processSimulationResult(context, simulatePacketIn(context))
        }
    }

    private def setVportForLocalTraffic(context: PacketContext): Unit = {
        val inPortNo = context.origMatch.getInputPortNumber
        val inPortId = dpState getVportForDpPortNumber Unsigned.unsign(inPortNo)
        context.inputPort = inPortId.orNull
    }

    /*
     * Take the outgoing filter for each port and apply it, checking for
     * Action.ACCEPT.
     *
     * Aux. to handlePacketToPortSet.
     */
    private def applyOutgoingFilter(context: PacketContext,
                                    localPorts: Seq[Port]): Urgent[Boolean] =
        applyOutboundFilters(localPorts, context) map {
            portNumbers =>
                val actions = towardsLocalDpPorts(portNumbers, context.flowTags,
                                                  context)
                addTranslatedFlowForActions(context, actions)
                true
        }

    /** The packet arrived on a tunnel but didn't match in the WFT. It's either
      * addressed (by the tunnel key) to a local PortSet or it was mistakenly
      * routed here. Map the tunnel key to a port set (through the
      * VirtualToPhysicalMapper).
      */
    private def handlePacketToPortSet(context: PacketContext)
    : Urgent[PipelinePath] = {
        context.log.debug("packet from a tunnel port towards a port set")
        // We currently only handle packets ingressing on tunnel ports if they
        // have a tunnel key. If the tunnel key corresponds to a local virtual
        // port then the pre-installed flow rules should have matched the
        // packet. So we really only handle cases where the tunnel key exists
        // and corresponds to a port set.

        val req = PortSetForTunnelKeyRequest(context.origMatch.getTunnelID)
        val portSet = VirtualToPhysicalMapper expiringAsk req
        portSet map {
            case null =>
                throw new Exception("null portSet")
            case pSet =>
                context.log.debug("tun => portSet: {}", pSet)
                // egress port filter simulation
                activePorts(pSet.localPorts, context.flowTags, context) flatMap {
                    applyOutgoingFilter(context, _)
                }
                PacketToPortSet
        }
    }

    private def doEgressPortSimulation(context: PacketContext) = {
        context.log.debug("Handling generated packet")
        processSimulationResult(context, runSimulation(context))
    }

    /*
     * Here we receive the result of the simulation, which can be either a
     * simulation that could complete with resources cached locally, or the
     * first incomplete future that was found in the way.
     *
     * This will enqueue the simulation for later processing whenever the future
     * completes, or proceed with the resulting actions if the result is
     * already computed.
     */
    def processSimulationResult(context: PacketContext,
                                result: Urgent[SimulationResult])
    : Urgent[PipelinePath] = {

        result flatMap {
            case AddVirtualWildcardFlow(flow) =>
                val urgent = addVirtualWildcardFlow(context, flow)
                if (urgent.notReady) {
                    context.log.debug(
                        "AddVirtualWildcardFlow, postponing, running callbacks")
                }
                urgent
            case SendPacket(actions) =>
                context.runFlowRemovedCallbacks()
                sendPacket(context, actions)
            case NoOp =>
                context.runFlowRemovedCallbacks()
                addToActionsCacheAndInvalidate(context, Nil)
                resultLogger.debug(s"no-op for match ${context.origMatch} " +
                                   s"tags ${context.flowTags}")
                Ready(null)
            case TemporaryDrop =>
                context.clearFlowTags()
                addTranslatedFlowForActions(context, Nil,
                                            TEMPORARY_DROP_MILLIS, 0)
                Ready(null)
            case Drop =>
                addTranslatedFlowForActions(context, Nil,
                                            0, IDLE_EXPIRATION_MILLIS)
                Ready(null)
        } map { _ => Simulation }
    }

    private def simulatePacketIn(context: PacketContext): Urgent[SimulationResult] =
        if (context.inputPort ne null) {
            val packet = context.packet
            system.eventStream.publish(
                PacketIn(context.origMatch.clone(), packet.getEthernet,
                    packet.getMatch, packet.getReason,
                    context.cookieOrEgressPort.left getOrElse 0))

            handleDHCP(context) flatMap {
                case true => Ready(NoOp)
                case false => runSimulation(context)
            }
        } else {
            Ready(TemporaryDrop)
        }

    def addVirtualWildcardFlow(context: PacketContext,
                               flow: WildcardFlow): Urgent[_] = {
        translateVirtualWildcardFlow(context, flow) map {
            addTranslatedFlow(context, _)
        }
    }

    private def handleStateMessage(context: PacketContext): Urgent[PipelinePath] = {
        context.log.debug("Accepting a state push message")
        replicator.accept(context.ethernet)
        Ready(StateMessage)
    }

    private def handleDHCP(context: PacketContext): Urgent[Boolean] = {
        def isUdpDhcpFlowKey(k: FlowKey): Boolean = k match {
            case udp: FlowKeyUDP => (udp.getUdpSrc == 68) && (udp.getUdpDst == 67)
            case _ => false
        }

        def payloadAs[T](pkt: IPacket)(implicit tag: ClassTag[T]): Option[T] = {
            val payload = pkt.getPayload
            if (tag.runtimeClass == payload.getClass)
                Some(payload.asInstanceOf[T])
            else
                None
        }

        if (context.packet.getMatch.getKeys.filter(isUdpDhcpFlowKey).isEmpty)
            return Ready(false)

        (for {
            ip4 <- payloadAs[IPv4](context.packet.getEthernet)
            udp <- payloadAs[UDP](ip4)
            dhcp <- payloadAs[DHCP](udp)
            if dhcp.getOpCode == DHCP.OPCODE_REQUEST
        } yield {
            processDhcpFuture(context, dhcp)
        }) getOrElse Ready(false)
    }

    private def processDhcpFuture(context: PacketContext,
                                  dhcp: DHCP): Urgent[Boolean] =
        VirtualTopologyActor.expiringAsk[Port](context.inputPort, context.log) map {
            processDhcp(context, _, dhcp, DatapathController.minMtu)
        }

    private def processDhcp(context: PacketContext, inPort: Port,
                            dhcp: DHCP, mtu: Short): Boolean = {
        val srcMac = context.packet.getEthernet.getSourceMACAddress
        val optMtu = Option(mtu)
        DhcpImpl(dataClient, inPort, dhcp, srcMac, optMtu, context.log) match {
            case Some(dhcpReply) =>
                context.log.debug(
                    "sending DHCP reply {} to port {}", dhcpReply, inPort.id)
                PacketsEntryPoint !
                    EmitGeneratedPacket(inPort.id, dhcpReply, context.flowCookie)
                true
            case None =>
                false
        }
    }

    private def sendPacket(context: PacketContext,
                           acts: List[FlowAction]): Urgent[_] = {
        context.log.debug("Sending with actions {}", acts)
        resultLogger.debug(s"Match ${context.origMatch} send with actions $acts " +
                           s"visited tags ${context.flowTags}")
        translateActions(context, acts) map { executePacket(context, _) }
    }

    private def addToActionsCacheAndInvalidate(context: PacketContext,
                                               actions: JList[FlowAction]): Unit = {
        val wm = context.packet.getMatch
        actionsCache.actions.put(wm, actions)
        actionsCache.pending(actionsCache.getSlot()) = wm
    }

    private def datapathConn(context: PacketContext) =
        dpConnPool.get(context.packet.getMatch.hashCode)
}
