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
package org.midonet.midolman

import java.util.{UUID, List => JList}

import scala.collection.JavaConversions._
import scala.reflect.ClassTag

import akka.actor._
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.cluster.DataClient
import org.midonet.cluster.client.Port
import org.midonet.midolman.DeduplicationActor.ActionsCache
import org.midonet.midolman.PacketWorkflow.{PipelinePath, TemporaryDrop}
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.simulation.{Coordinator, DhcpImpl, PacketContext}
import org.midonet.midolman.state.FlowStateReplicator
import org.midonet.midolman.topology.{VirtualTopologyActor, VxLanPortMapper}
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger.tagForDpPort
import org.midonet.sdn.flows.{FlowTagger, WildcardFlow}

trait PacketHandler {
    def start(context: PacketContext): PacketWorkflow.PipelinePath
    def drop(context: PacketContext): Unit
}

object PacketWorkflow {
    case class PacketIn(wMatch: FlowMatch,
                        inputPort: UUID,
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

    case object SendPacket extends SimulationResult

    case object AddVirtualWildcardFlow extends SimulationResult

    sealed trait PipelinePath
    case object WildcardTableHit extends PipelinePath
    case object PacketToPortSet extends PipelinePath
    case object StateMessage extends PipelinePath
    case object Simulation extends PipelinePath
    case object Error extends PipelinePath
}

trait UnderlayTrafficHandler { this: PacketWorkflow =>

    def handleFromTunnel(context: PacketContext, inPortNo: Int): PipelinePath = {
        if (dpState isOverlayTunnellingPort inPortNo) {
            if (context.isStateMessage)
                handleStateMessage(context)
            else
                handleFromUnderlay(context)
        } else {
            handleFromVtep(context)
        }
    }

    private def handleFromVtep(context: PacketContext): PipelinePath = {
        val portIdOpt = VxLanPortMapper uuidOf context.wcmatch.getTunnelKey.toInt
        context.inputPort = portIdOpt.orNull
        processSimulationResult(context, simulatePacketIn(context))
    }

    private def addActionsForTunnelPacket(context: PacketContext,
                                          forwardTo: DpPort): Unit = {
        val wmatch = context.origMatch
        context.wcmatch.clear()
        wmatch.reset(new FlowMatch()
            .setTunnelSrc(context.origMatch.getTunnelSrc)
            .setTunnelDst(context.origMatch.getTunnelDst)
            .setTunnelKey(context.origMatch.getTunnelKey))

        context.addFlowTag(FlowTagger.tagForTunnelKey(wmatch.getTunnelKey))
        context.addFlowTag(FlowTagger.tagForDpPort(forwardTo.getPortNo))
        context.addFlowTag(FlowTagger.tagForTunnelRoute(
                            wmatch.getTunnelSrc, wmatch.getTunnelDst))
        context.addFlowAction(forwardTo.toOutputAction)
        context.idleExpirationMillis = 300 * 1000
    }

    private def handleFromUnderlay(context: PacketContext): PipelinePath = {
        val tunnelKey = context.wcmatch.getTunnelKey
        dpState.dpPortNumberForTunnelKey(tunnelKey) match {
            case Some(dpPort) =>
                addActionsForTunnelPacket(context, dpPort)
                addTranslatedFlow(context)
                PacketWorkflow.WildcardTableHit

            case None =>
                processSimulationResult(context, TemporaryDrop)
                PacketWorkflow.Error
        }
    }
}

class PacketWorkflow(protected val dpState: DatapathState,
                     val datapath: Datapath,
                     val dataClient: DataClient,
                     val dpChannel: DatapathChannel,
                     val cbExecutor: CallbackExecutor,
                     val actionsCache: ActionsCache,
                     val replicator: FlowStateReplicator)
                    (implicit val system: ActorSystem)
        extends FlowTranslator with PacketHandler with UnderlayTrafficHandler {
    import DeduplicationActor._
    import FlowController.{AddWildcardFlow, FlowAdded}
    import PacketWorkflow._

    val ERROR_CONDITION_HARD_EXPIRATION = 10000

    val resultLogger = Logger(LoggerFactory.getLogger("org.midonet.packets.results"))

    override def start(context: PacketContext): PipelinePath = {
        context.prepareForSimulation(FlowController.lastInvalidationEvent)
        context.log.debug(s"Initiating processing, attempt: ${context.runs}")
        val res = if (context.ingressed)
                    handlePacketWithCookie(context)
                  else
                    doEgressPortSimulation(context)
        res
    }

    override def drop(context: PacketContext) {
        context.prepareForDrop(FlowController.lastInvalidationEvent)
        context.idleExpirationMillis = 0
        context.hardExpirationMillis = ERROR_CONDITION_HARD_EXPIRATION
        addTranslatedFlow(context)
    }

    def logResultNewFlow(msg: String, context: PacketContext, wflow: WildcardFlow) {
        resultLogger.debug(s"$msg: match ${wflow.getMatch} will create flow with " +
            s"actions ${wflow.actions}, and tags ${context.flowTags}")
    }

    def logResultMatchedFlow(msg: String, context: PacketContext, wflow: WildcardFlow) {
        resultLogger.debug(s"$msg: match ${wflow.getMatch} with actions ${wflow.actions}")
    }

    def runSimulation(context: PacketContext): SimulationResult =
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
                                context.flowRemovedCallbacks,
                                context.flowTags, context.lastInvalidation,
                                context.packet.getMatch, actionsCache.pending,
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
        dpChannel.createFlow(dpFlow)
        notifyFlowAdded(context, dpFlow, newWildFlow)
    }

    protected def addTranslatedFlow(context: PacketContext): Unit = {
        if (context.packet.getReason == Packet.Reason.FlowActionUserspace) {
            resultLogger.debug("packet came up due to userspace dp action, " +
                               s"match ${context.origMatch}")
            context.runFlowRemovedCallbacks()
        } else {
            // ApplyState needs to happen before we add the wildcard flow
            // because it adds callbacks to the PacketContext and it can also
            // result in a NotYet exception being thrown.
            applyState(context)
            handleFlow(context)
        }

        dpChannel.executePacket(context.packet, context.flowActions)
    }

    private def handleFlow(context: PacketContext): Unit = {
        if (context.isGenerated) {
            context.log.warn(s"Tried to add a flow for a generated packet")
            context.runFlowRemovedCallbacks()
            return
        }

        context.origMatch.propagateUserspaceFieldsOf(context.wcmatch)
        val wildFlow = WildcardFlow(
            wcmatch = context.origMatch,
            hardExpirationMillis = context.hardExpirationMillis,
            idleExpirationMillis = context.idleExpirationMillis,
            actions = context.flowActions.toList,
            priority = 0,
            cbExecutor = cbExecutor)

        if (context.wcmatch.userspaceFieldsSeen) {
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

    def applyState(context: PacketContext): Unit =
        if (!context.isDrop) {
            context.log.debug("Applying connection state")
            replicator.accumulateNewKeys(context.state.conntrackTx,
                                         context.state.natTx,
                                         context.inputPort,
                                         context.outPorts,
                                         context.flowTags,
                                         context.flowRemovedCallbacks)
            replicator.pushState(dpChannel)
            context.state.conntrackTx.commit()
            context.state.natTx.commit()
    }

    private def handlePacketWithCookie(context: PacketContext): PipelinePath = {
        if (!context.origMatch.isUsed(Field.InputPortNumber)) {
            context.log.error("packet had no inPort number")
            return Error
        }

        val inPortNo = context.origMatch.getInputPortNumber
        context.flowTags.add(tagForDpPort(inPortNo))

        if (context.packet.getReason == Packet.Reason.FlowActionUserspace) {
            setVportForLocalTraffic(context, inPortNo)
            processSimulationResult(context, simulatePacketIn(context))
        } else {
            FlowController.queryWildcardFlowTable(context.origMatch) match {
                case Some(wildflow) =>
                    handleWildcardTableMatch(context, wildflow)
                    WildcardTableHit
                case None =>
                    context.log.debug("missed the wildcard flow table")
                    handleWildcardTableMiss(context, inPortNo)
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

        dpChannel.executePacket(context.packet, wildFlow.actions)
    }

    /** Handles a packet that missed the wildcard flow table.
      *
      * If the FlowMatch indicates that the packet came from a tunnel port,
      * there are 3 possible situations:
      *
      *     1) it came from the overlay tunneling port and is a
      *        state message sent by a fellow agent.
      *     2) it came from the overlay tunneling port and it corresponds
      *        to an unknown port tunnel key, probably due to a race
      *        between a packet and a port deactivation. We'll install a
      *        temporary drop flow.
      *     3) it's coming from a VTEP into the virtual network, we shall
      *        simulate it.
      *
      * Otherwise, the packet is coming in from a regular port into the
      * virtual network and we'll simulate it.
      */
    private def handleWildcardTableMiss(context: PacketContext,
                                        inPortNo: Int): PipelinePath = {
        if (context.origMatch.isFromTunnel) {
            handleFromTunnel(context, inPortNo)
        } else {
            setVportForLocalTraffic(context, inPortNo)
            processSimulationResult(context, simulatePacketIn(context))
        }
    }

    private def setVportForLocalTraffic(context: PacketContext,
                                        inPortNo: Int): Unit = {
        val inPortId = dpState getVportForDpPortNumber inPortNo
        context.inputPort = inPortId.orNull
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
                                result: SimulationResult): PipelinePath = {
        result match {
            case AddVirtualWildcardFlow =>
                addVirtualWildcardFlow(context)
            case SendPacket =>
                context.runFlowRemovedCallbacks()
                sendPacket(context)
            case NoOp =>
                context.runFlowRemovedCallbacks()
                addToActionsCacheAndInvalidate(context, Nil)
                resultLogger.debug(s"no-op for match ${context.origMatch} " +
                                   s"tags ${context.flowTags}")
            case TemporaryDrop =>
                context.clearFlowTags()
                context.idleExpirationMillis = 0
                context.hardExpirationMillis = TEMPORARY_DROP_MILLIS
                addTranslatedFlow(context)
            case Drop =>
                context.idleExpirationMillis = IDLE_EXPIRATION_MILLIS
                context.hardExpirationMillis = 0
                addTranslatedFlow(context)
        }
        Simulation
    }

    protected def simulatePacketIn(context: PacketContext): SimulationResult =
        if (context.inputPort ne null) {
            val packet = context.packet
            system.eventStream.publish(
                PacketIn(context.origMatch.clone(), context.inputPort,
                         packet.getEthernet,
                         packet.getMatch, packet.getReason,
                         context.cookieOrEgressPort.left getOrElse 0))

            if (handleDHCP(context)) {
                NoOp
            } else {
                runSimulation(context)
            }
        } else {
            TemporaryDrop
        }

    def addVirtualWildcardFlow(context: PacketContext): Unit = {
        translateActions(context)
        addTranslatedFlow(context)
    }

    protected def handleStateMessage(context: PacketContext): PipelinePath = {
        context.log.debug("Accepting a state push message")
        replicator.accept(context.ethernet)
        StateMessage
    }

    private def handleDHCP(context: PacketContext): Boolean = {
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
            return false

        (for {
            ip4 <- payloadAs[IPv4](context.packet.getEthernet)
            udp <- payloadAs[UDP](ip4)
            dhcp <- payloadAs[DHCP](udp)
            if dhcp.getOpCode == DHCP.OPCODE_REQUEST
        } yield {
            val port = VirtualTopologyActor.tryAsk[Port](context.inputPort)
            processDhcp(context, port, dhcp, DatapathController.minMtu)
        }) getOrElse false
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

    private def sendPacket(context: PacketContext): Unit = {
        context.log.debug(s"Sending with actions ${context.virtualFlowActions}")
        resultLogger.debug(s"Match ${context.origMatch} send with actions " +
                           s"${context.virtualFlowActions}; visited tags ${context.flowTags}")
        translateActions(context)
        dpChannel.executePacket(context.packet, context.flowActions)
    }

    private def addToActionsCacheAndInvalidate(context: PacketContext,
                                               actions: JList[FlowAction]): Unit = {
        val wm = context.packet.getMatch
        actionsCache.actions.put(wm, actions)
        actionsCache.pending(actionsCache.getSlot()) = wm
    }
}
