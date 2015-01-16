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

import java.util.UUID

import scala.collection.JavaConversions._

import akka.actor._

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.cluster.DataClient
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.routingprotocols.RoutingWorkflow
import org.midonet.midolman.simulation.{Coordinator, DhcpImpl, PacketContext}
import org.midonet.midolman.state.FlowStateReplicator
import org.midonet.midolman.topology.devices.Port
import org.midonet.midolman.topology.{VirtualTopologyActor, VxLanPortMapper}
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp._
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger.tagForDpPort
import org.midonet.sdn.flows.{FlowTagger, WildcardFlow}

trait PacketHandler {
    def start(context: PacketContext): PacketWorkflow.SimulationResult
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

    trait SimulationResult
    case object NoOp extends SimulationResult
    case object Drop extends SimulationResult
    case object TemporaryDrop extends SimulationResult
    case object SendPacket extends SimulationResult
    case object AddVirtualWildcardFlow extends SimulationResult
    case object StateMessage extends SimulationResult
    case object UserspaceFlow extends SimulationResult
    case object FlowCreated extends SimulationResult
    case object GeneratedPacket extends SimulationResult
}

trait UnderlayTrafficHandler { this: PacketWorkflow =>
    import PacketWorkflow._

    def handleFromTunnel(context: PacketContext, inPortNo: Int): SimulationResult = {
        if (dpState isOverlayTunnellingPort inPortNo) {
            if (context.isStateMessage)
                handleStateMessage(context)
            else
                handleFromUnderlay(context)
        } else {
            handleFromVtep(context)
        }
    }

    private def handleFromVtep(context: PacketContext): SimulationResult = {
        val portIdOpt = VxLanPortMapper uuidOf context.wcmatch.getTunnelKey.toInt
        context.inputPort = portIdOpt.orNull
        processSimulationResult(context, simulatePacketIn(context))
    }

    private def addActionsForTunnelPacket(context: PacketContext,
                                          forwardTo: DpPort): Unit = {
        val origMatch = context.origMatch
        context.addFlowTag(FlowTagger.tagForTunnelKey(origMatch.getTunnelKey))
        context.addFlowTag(FlowTagger.tagForDpPort(forwardTo.getPortNo))
        context.addFlowTag(FlowTagger.tagForTunnelRoute(
                           origMatch.getTunnelSrc, origMatch.getTunnelDst))
        context.addFlowAction(forwardTo.toOutputAction)
        context.idleExpirationMillis = 300 * 1000
    }

    private def handleFromUnderlay(context: PacketContext): SimulationResult = {
        val tunnelKey = context.wcmatch.getTunnelKey
        dpState.dpPortNumberForTunnelKey(tunnelKey) match {
            case Some(dpPort) =>
                addActionsForTunnelPacket(context, dpPort)
                addTranslatedFlow(context)
            case None =>
                processSimulationResult(context, TemporaryDrop)
        }
    }
}

class PacketWorkflow(protected val dpState: DatapathState,
                     val datapath: Datapath,
                     val dataClient: DataClient,
                     val dpChannel: DatapathChannel,
                     val replicator: FlowStateReplicator)
                    (implicit val system: ActorSystem)
        extends PacketHandler with FlowTranslator
        with RoutingWorkflow with UnderlayTrafficHandler {

    import FlowController.{AddWildcardFlow, FlowAdded}
    import PacketWorkflow._

    val ERROR_CONDITION_HARD_EXPIRATION = 10000

    val resultLogger = Logger(LoggerFactory.getLogger("org.midonet.packets.results"))

    override def start(context: PacketContext): SimulationResult = {
        context.prepareForSimulation(FlowController.lastInvalidationEvent)
        context.log.debug(s"Initiating processing, attempt: ${context.runs}")
        if (context.ingressed)
            handlePacketWithCookie(context)
        else
            doEgressPortSimulation(context)
    }

    override def drop(context: PacketContext): Unit = {
        context.prepareForDrop(FlowController.lastInvalidationEvent)
        context.idleExpirationMillis = 0
        context.hardExpirationMillis = ERROR_CONDITION_HARD_EXPIRATION
        addTranslatedFlow(context)
    }

    def logResultNewFlow(msg: String, context: PacketContext, wflow: WildcardFlow): Unit = {
        resultLogger.debug(s"$msg: match ${wflow.getMatch}, actions " +
                           s"${wflow.actions}, and tags ${context.flowTags}")
    }

    def logResultMatchedFlow(msg: String, context: PacketContext, wflow: WildcardFlow): Unit = {
        resultLogger.debug(s"$msg: match ${wflow.getMatch} with actions ${wflow.actions}")
    }

    def runSimulation(context: PacketContext): SimulationResult =
        new Coordinator(context).simulate()

    private def notifyFlowAdded(context: PacketContext,
                                flow: Flow,
                                newWildFlow: Option[WildcardFlow]): Unit = {
        context.log.debug("Successfully created flow")
        newWildFlow match {
            case None =>
                FlowController ! FlowAdded(flow, context.origMatch)
            case Some(wf) =>
                FlowController ! AddWildcardFlow(wf, flow,
                                context.flowRemovedCallbacks,
                                context.flowTags, context.lastInvalidation,
                                context.origMatch)
        }
    }

    private def createFlow(context: PacketContext,
                           wildFlow: WildcardFlow,
                           newWildFlow: Option[WildcardFlow] = None): Unit = {
        val dpFlow = new Flow(context.origMatch, wildFlow.getActions)
        context.log.debug(s"Creating flow $dpFlow from $wildFlow")
        dpChannel.createFlow(dpFlow)
        notifyFlowAdded(context, dpFlow, newWildFlow)
    }

    protected def addTranslatedFlow(context: PacketContext): SimulationResult = {
        val res =
            if (context.packet.getReason == Packet.Reason.FlowActionUserspace) {
                resultLogger.debug("packet came up due to userspace dp action, " +
                                   s"match ${context.origMatch}")
                context.runFlowRemovedCallbacks()
                UserspaceFlow
            } else {
                // ApplyState needs to happen before we add the wildcard flow
                // because it adds callbacks to the PacketContext and it can also
                // result in a NotYet exception being thrown.
                applyState(context)
                handleFlow(context)
            }

        dpChannel.executePacket(context.packet, context.flowActions)
        res
    }

    private def handleFlow(context: PacketContext): SimulationResult = {
        if (context.isGenerated) {
            context.log.warn(s"Tried to add a flow for a generated packet")
            context.runFlowRemovedCallbacks()
            return GeneratedPacket
        }

        context.origMatch.propagateSeenFieldsFrom(context.wcmatch)
        val wildFlow = WildcardFlow(
            wcmatch = context.origMatch,
            hardExpirationMillis = context.hardExpirationMillis,
            idleExpirationMillis = context.idleExpirationMillis,
            actions = context.flowActions.toList,
            priority = 0,
            cbExecutor = context.callbackExecutor)

        if (context.wcmatch.userspaceFieldsSeen) {
            context.log.debug("Userspace fields seen; skipping flow creation")
            context.runFlowRemovedCallbacks()
            UserspaceFlow
        } else {
            logResultNewFlow("will create flow", context, wildFlow)
            createFlow(context, wildFlow, Some(wildFlow))
            FlowCreated
        }
    }

    def applyState(context: PacketContext): Unit =
        if (!context.isDrop) {
            context.log.debug("Applying connection state")
            replicator.accumulateNewKeys(context.conntrackTx,
                                         context.natTx,
                                         context.inputPort,
                                         context.outPorts,
                                         context.flowTags,
                                         context.flowRemovedCallbacks)
            replicator.pushState(dpChannel)
            context.conntrackTx.commit()
            context.natTx.commit()
    }

    private def handlePacketWithCookie(context: PacketContext): SimulationResult = {
        if (!context.origMatch.isUsed(Field.InputPortNumber)) {
            context.log.error("packet had no inPort number")
            processSimulationResult(context, TemporaryDrop)
        }

        val inPortNo = context.origMatch.getInputPortNumber
        context.flowTags.add(tagForDpPort(inPortNo))

        if (context.packet.getReason == Packet.Reason.FlowActionUserspace) {
            processSimulationResult(context,
                if (resolveVport(context, inPortNo))
                    simulatePacketIn(context)
                else
                    Drop)
        } else {
            FlowController.queryWildcardFlowTable(context.origMatch) match {
                case Some(wildflow) =>
                    handleWildcardTableMatch(context, wildflow)
                    AddVirtualWildcardFlow
                case None =>
                    context.log.debug("missed the wildcard flow table")
                    handleWildcardTableMiss(context, inPortNo)
            }
        }
    }

    def handleWildcardTableMatch(context: PacketContext,
                                 wildFlow: WildcardFlow): Unit = {
        context.log.debug("matched a wildcard flow with actions {}", wildFlow.actions)
        logResultMatchedFlow("matched a wildcard flow", context, wildFlow)
        context.origMatch.propagateSeenFieldsFrom(wildFlow.wcmatch)
        createFlow(context, wildFlow)
        dpChannel.executePacket(context.packet, wildFlow.getActions)

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
                                        inPortNo: Int): SimulationResult =
        if (context.origMatch.isFromTunnel) {
            handleFromTunnel(context, inPortNo)
        } else if (resolveVport(context, inPortNo)) {
            processSimulationResult(context, simulatePacketIn(context))
        } else {
            processSimulationResult(context, handleBgp(context, inPortNo))
        }

    private def resolveVport(context: PacketContext, inPortNo: Int): Boolean = {
        val inPortId = dpState getVportForDpPortNumber inPortNo
        if (inPortId.isDefined) {
            context.inputPort = inPortId.get
            true
        } else {
            false
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
                                result: SimulationResult): SimulationResult = {
        result match {
            case AddVirtualWildcardFlow =>
                addVirtualWildcardFlow(context)
            case SendPacket =>
                context.runFlowRemovedCallbacks()
                sendPacket(context)
                GeneratedPacket
            case NoOp =>
                context.runFlowRemovedCallbacks()
                resultLogger.debug(s"no-op for match ${context.origMatch} " +
                                   s"tags ${context.flowTags}")
                NoOp
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
    }

    protected def simulatePacketIn(context: PacketContext): SimulationResult = {
        val packet = context.packet
        system.eventStream.publish(
            PacketIn(context.origMatch.clone(), context.inputPort,
                     packet.getEthernet,
                     packet.getMatch, packet.getReason,
                     context.cookie))

        if (handleDHCP(context)) {
            NoOp
        } else {
            runSimulation(context)
        }
    }

    def addVirtualWildcardFlow(context: PacketContext): SimulationResult = {
        translateActions(context)
        addTranslatedFlow(context)
    }

    protected def handleStateMessage(context: PacketContext): SimulationResult = {
        context.log.debug("Accepting a state push message")
        replicator.accept(context.ethernet)
        StateMessage
    }

    private def handleDHCP(context: PacketContext): Boolean = {
        val fmatch = context.origMatch
        val isDhcp = fmatch.getEtherType == IPv4.ETHERTYPE &&
                     fmatch.getNetworkProto == UDP.PROTOCOL_NUMBER &&
                     fmatch.getSrcPort == 68 && fmatch.getDstPort == 67

        if (!isDhcp)
            return false

        val port = VirtualTopologyActor.tryAsk[Port](context.inputPort)
        val dhcp = context.packet.getEthernet.getPayload.getPayload.getPayload.asInstanceOf[DHCP]
        dhcp.getOpCode == DHCP.OPCODE_REQUEST &&
            processDhcp(context, port, dhcp, DatapathController.minMtu)
    }

    private def processDhcp(context: PacketContext, inPort: Port,
                            dhcp: DHCP, mtu: Short): Boolean = {
        val srcMac = context.origMatch.getEthSrc
        val optMtu = Option(mtu)
        DhcpImpl(dataClient, inPort, dhcp, srcMac, optMtu, context.log) match {
            case Some(dhcpReply) =>
                context.log.debug(
                    "sending DHCP reply {} to port {}", dhcpReply, inPort.id)
                context.addGeneratedPacket(inPort.id, dhcpReply)
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
}
