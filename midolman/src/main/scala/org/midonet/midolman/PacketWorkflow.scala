/*
 * Copyright 2014 - 2015 Midokura SARL
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

import akka.actor._

import com.typesafe.scalalogging.Logger
import org.midonet.midolman.flows.FlowInvalidation
import org.slf4j.LoggerFactory

import org.midonet.cluster.DataClient
import org.midonet.midolman.config.MidolmanConfig
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
import org.midonet.sdn.flows.FlowTagger

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

    val TEMPORARY_DROP_EXPIRATION = 5 * 1000
    val EXPIRATION_MILLIS = 60 * 1000
    val ERROR_CONDITION_EXPIRATION = 10 * 1000

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
        val srcTunIp = IPv4Addr(context.wcmatch.getTunnelSrc)
        val vni   = context.wcmatch.getTunnelKey.toInt
        val portIdOpt = VxLanPortMapper uuidOf (srcTunIp, vni)
        context.inputPort = portIdOpt.orNull
        val simResult = if (context.inputPort != null) {
            simulatePacketIn(context)
        } else {
            context.log.info("VNI doesn't map to any VxLAN port")
            Drop
        }
        processSimulationResult(context, simResult)
    }

    private def addActionsForTunnelPacket(context: PacketContext,
                                          forwardTo: DpPort): Unit = {
        val origMatch = context.origMatch
        context.addFlowTag(FlowTagger.tagForTunnelKey(origMatch.getTunnelKey))
        context.addFlowTag(FlowTagger.tagForDpPort(forwardTo.getPortNo))
        context.addFlowTag(FlowTagger.tagForTunnelRoute(
                           origMatch.getTunnelSrc, origMatch.getTunnelDst))
        context.addFlowAndPacketAction(forwardTo.toOutputAction)
        context.expiration = 300 * 1000
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
                     datapath: Datapath,
                     dataClient: DataClient,
                     dpChannel: DatapathChannel,
                     replicator: FlowStateReplicator,
                     config: MidolmanConfig)
                    (implicit val system: ActorSystem)
        extends PacketHandler with FlowTranslator
        with RoutingWorkflow with UnderlayTrafficHandler {
    import PacketWorkflow._

    val resultLogger = Logger(LoggerFactory.getLogger("org.midonet.packets.results"))

    override def start(context: PacketContext): SimulationResult = {
        context.prepareForSimulation(FlowInvalidation.lastInvalidationEvent)
        context.log.debug(s"Initiating processing, attempt: ${context.runs}")
        if (context.ingressed)
            handlePacketIngress(context)
        else
            handlePacketEgress(context)
    }

    override def drop(context: PacketContext): Unit = {
        context.prepareForDrop(FlowInvalidation.lastInvalidationEvent)
        context.expiration = ERROR_CONDITION_EXPIRATION
        addTranslatedFlow(context)
    }

    def logResultNewFlow(msg: String, context: PacketContext): Unit = {
        resultLogger.debug(s"$msg: match ${context.origMatch}, actions " +
                           s"${context.flowActions}, tags ${context.flowTags}")
    }

    def runSimulation(context: PacketContext): SimulationResult =
        new Coordinator(context).simulate()

    protected def addTranslatedFlow(context: PacketContext): SimulationResult =
        if (context.packet.getReason == Packet.Reason.FlowActionUserspace) {
            resultLogger.debug("packet came up due to userspace dp action, " +
                               s"match ${context.origMatch}")
            context.runFlowRemovedCallbacks()
            UserspaceFlow
        } else {
            applyState(context)
            dpChannel.executePacket(context.packet, context.packetActions)
            handleFlow(context)
        }

    private def handleFlow(context: PacketContext): SimulationResult =
        if (context.isGenerated) {
            context.log.warn(s"Tried to add a flow for a generated packet")
            context.runFlowRemovedCallbacks()
            GeneratedPacket
        } else if (context.origMatch.isFromTunnel && context.tracingEnabled) {
            // don't create a flow for traced contexts on the egress host
            context.log.warn("Skipping flow creation for traced flow on egress")
            context.runFlowRemovedCallbacks()
            SendPacket
        } else {
            logResultNewFlow("will create flow", context)
            context.origMatch.propagateSeenFieldsFrom(context.wcmatch)
            if (context.wcmatch.userspaceFieldsSeen) {
                context.log.debug("Userspace fields seen; skipping flow creation")
                context.runFlowRemovedCallbacks()
                UserspaceFlow
            } else {
                val dpFlow = new Flow(context.origMatch, context.flowActions)
                context.log.debug(s"Creating flow $dpFlow")
                dpChannel.createFlow(dpFlow)
                FlowController ! context
                FlowCreated
            }
        }

    def applyState(context: PacketContext): Unit =
        if (!context.isDrop) {
            context.log.debug("Applying connection state")
            context.addModifiedTraceKeys
            replicator.accumulateNewKeys(context.conntrackTx,
                                         context.natTx,
                                         context.traceTx,
                                         context.inputPort,
                                         context.outPorts,
                                         context.flowTags,
                                         context.flowRemovedCallbacks)
            replicator.pushState(dpChannel)
            context.conntrackTx.commit()
            context.natTx.commit()
            context.traceTx.commit()
    }

    private def handlePacketIngress(context: PacketContext): SimulationResult = {
        if (!context.origMatch.isUsed(Field.InputPortNumber)) {
            context.log.error("packet had no inPort number")
            processSimulationResult(context, TemporaryDrop)
        }

        val inPortNo = context.origMatch.getInputPortNumber
        context.flowTags.add(tagForDpPort(inPortNo))

        if (context.origMatch.isFromTunnel) {
            handleFromTunnel(context, inPortNo)
        } else if (resolveVport(context, inPortNo)) {
            processSimulationResult(context, simulatePacketIn(context))
        } else {
            processSimulationResult(context, handleBgp(context, inPortNo))
        }
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

    private def handlePacketEgress(context: PacketContext) = {
        context.log.debug("Handling generated packet")
        processSimulationResult(context, runSimulation(context))
    }

    def processSimulationResult(context: PacketContext,
                                result: SimulationResult): SimulationResult = {
        result match {
            case AddVirtualWildcardFlow =>
                context.expiration = EXPIRATION_MILLIS
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
                context.expiration = TEMPORARY_DROP_EXPIRATION
                addTranslatedFlow(context)
            case Drop =>
                context.expiration = EXPIRATION_MILLIS
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
            processDhcp(context, port, dhcp,
                config.getDhcpMtu.toShort.min(DatapathController.minMtu))
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
        dpChannel.executePacket(context.packet, context.packetActions)
    }
}
