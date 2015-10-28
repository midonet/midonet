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

import java.lang.{Integer => JInteger}
import java.util.UUID

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.Failure

import akka.actor._

import com.typesafe.scalalogging.Logger

import org.slf4j.{LoggerFactory, MDC}

import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.{DatapathChannel, FlowProcessor}
import org.midonet.midolman.flows.FlowExpirationIndexer
import org.midonet.midolman.flows.FlowExpirationIndexer.Expiration
import org.midonet.midolman.logging.{ActorLogWithoutPath, FlowTracingContext}
import org.midonet.midolman.management.PacketTracing
import org.midonet.midolman.monitoring.FlowRecorder
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.openstack.metadata.MetadataServiceWorkflow
import org.midonet.midolman.routingprotocols.RoutingWorkflow
import org.midonet.midolman.simulation.{Port, _}
import org.midonet.midolman.SimulationBackChannel.BackChannelMessage
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.state.{FlowStatePackets, FlowStateReplicator, FlowStateStorage, NatLeaser, _}
import org.midonet.midolman.topology.RouterMapper.InvalidateFlows
import org.midonet.midolman.topology.{VirtualTopology, VxLanPortMappingService}
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp._
import org.midonet.odp.flows.FlowActions.output
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger._
import org.midonet.sdn.state.{FlowStateTable, FlowStateTransaction}
import org.midonet.util.collection.{IPv4InvalidationArray, Reducer}
import org.midonet.util.concurrent._

object PacketWorkflow {
    case class HandlePackets(packet: Array[Packet])
    case class RestartWorkflow(context: PacketContext, error: Throwable)
        extends BackChannelMessage

    sealed trait GeneratedPacket extends BackChannelMessage {
        val eth: Ethernet
        val cookie: Long
    }
    case class GeneratedLogicalPacket(egressPort: UUID, eth: Ethernet, cookie: Long)
        extends GeneratedPacket
    case class GeneratedPhysicalPacket(egressPort: JInteger, eth: Ethernet, cookie: Long)
        extends GeneratedPacket

    trait SimulationResult {
        val simStep: SimStep = context => this
    }

    type SimStep = PacketContext => SimulationResult

    sealed trait DropAction extends SimulationResult

    case object NoOp extends SimulationResult
    case object Drop extends DropAction
    case object ErrorDrop extends DropAction
    case object ShortDrop extends DropAction
    case object AddVirtualWildcardFlow extends SimulationResult
    case object StateMessage extends SimulationResult
    case object UserspaceFlow extends SimulationResult
    case object FlowCreated extends SimulationResult
    case object GeneratedPacket extends SimulationResult
}

class CookieGenerator(val start: Int, val increment: Int) {
    private var nextCookie = start

    def next: Int = {
        val ret = nextCookie
        nextCookie += increment
        ret
    }
}

trait UnderlayTrafficHandler { this: PacketWorkflow =>
    import PacketWorkflow._

    def handleFromTunnel(context: PacketContext, inPortNo: Int): SimulationResult =
        if (dpState isOverlayTunnellingPort inPortNo) {
            handleFromUnderlay(context)
        } else {
            handleFromVtep(context)
        }

    private def handleFromVtep(context: PacketContext): SimulationResult = {
        val srcTunIp = IPv4Addr(context.wcmatch.getTunnelSrc)
        val vni = context.wcmatch.getTunnelKey.toInt
        val portIdOpt = VxLanPortMappingService portOf (srcTunIp, vni)
        val simResult = if (portIdOpt.isDefined) {
            context.inputPort = portIdOpt.get
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
    }

    private def handleFromUnderlay(context: PacketContext): SimulationResult = {
        if (context.hasTraceTunnelBit) {
            context.enableTracingOnEgress()
            context.markUserspaceOnly()
        }
        context.log.debug(s"Received packet matching ${context.origMatch}" +
                           " from underlay")

        val tunnelKey = context.wcmatch.getTunnelKey
        dpState.dpPortForTunnelKey(tunnelKey) match {
            case null =>
                processSimulationResult(context, ErrorDrop)
            case dpPort =>
                addActionsForTunnelPacket(context, dpPort)
                addTranslatedFlow(context, FlowExpirationIndexer.TUNNEL_FLOW_EXPIRATION)
        }
    }
}

class PacketWorkflow(
            val config: MidolmanConfig,
            val hostId: UUID,
            val dpState: DatapathState,
            val cookieGen: CookieGenerator,
            val clock: NanoClock,
            val dpChannel: DatapathChannel,
            val dhcpConfigProvider: DhcpConfig,
            val backChannel: SimulationBackChannel,
            val flowProcessor: FlowProcessor,
            val connTrackStateTable: FlowStateTable[ConnTrackKey, ConnTrackValue],
            val natStateTable: FlowStateTable[NatKey, NatBinding],
            val traceStateTable: FlowStateTable[TraceKey, TraceContext],
            val peerResolver: PeerResolver,
            val storage: Future[FlowStateStorage],
            val natLeaser: NatLeaser,
            val metrics: PacketPipelineMetrics,
            val flowRecorder: FlowRecorder,
            val packetOut: Int => Unit)
        extends Actor with ActorLogWithoutPath with Stash with DisruptorBackChannel
        with UnderlayTrafficHandler with FlowTranslator with RoutingWorkflow
        with MetadataServiceWorkflow with FlowController {

    import PacketWorkflow._

    override def logSource = "org.midonet.packet-worker"
    val resultLogger = Logger(LoggerFactory.getLogger("org.midonet.packets.results"))

    implicit val dispatcher = this.context.system.dispatcher
    implicit val system = this.context.system

    protected val simulationExpireMillis = 5000L

    private val waitingRoom = new WaitingRoom[PacketContext](
                                        (simulationExpireMillis millis).toNanos)

    protected val connTrackTx = new FlowStateTransaction(connTrackStateTable)
    protected val natTx = new FlowStateTransaction(natStateTable)
    protected val traceStateTx = new FlowStateTransaction(traceStateTable)
    protected var replicator = new FlowStateReplicator(
            connTrackStateTable,
            natStateTable,
            traceStateTable,
            storage,
            hostId,
            peerResolver,
            dpState,
            this,
            config.datapath.controlPacketTos)

    protected val datapathId = dpState.datapath.getIndex

    protected val arpBroker = new ArpRequestBroker(config, backChannel,
                                                   () => self ! CheckBackchannels)

    private val invalidateExpiredConnTrackKeys =
        new Reducer[ConnTrackKey, ConnTrackValue, Unit]() {
            override def apply(u: Unit, k: ConnTrackKey, v: ConnTrackValue) {
                invalidateFlowsFor(k)
            }
        }

    private val invalidateExpiredNatKeys =
        new Reducer[NatKey, NatBinding, Unit]() {
            override def apply(u: Unit, k: NatKey, v: NatBinding): Unit = {
                invalidateFlowsFor(k)
                NatState.releaseBinding(k, v, natLeaser)
            }
        }

    system.scheduler.schedule(20 millis, 5 seconds, self, CheckBackchannels)

    override def receive = {
        case HandlePackets(packets) =>
            var i = 0
            while (i < packets.length && packets(i) != null) {
                handlePacket(packets(i))
                i += 1
            }
            process()

        case CheckBackchannels =>
            process()
    }

    override def shouldProcess(): Boolean =
        super.shouldProcess() ||
        backChannel.hasMessages ||
        arpBroker.shouldProcess()

    private def invalidateRoutedFlows(msg: InvalidateFlows) {
        val InvalidateFlows(id, added, deleted) = msg

        for (route <- deleted) {
            invalidateFlowsFor(FlowTagger.tagForRoute(route))
        }

        for (route <- added) {
            log.debug(s"Calculate flows invalidated by new route " +
            s"${route.getDstNetworkAddr}/${route.dstNetworkLength}")

            val deletions = IPv4InvalidationArray.current.deletePrefix(
                route.dstNetworkAddr, route.dstNetworkLength).iterator()
            while (deletions.hasNext) {
                val ip = IPv4Addr.fromInt(deletions.next)
                log.debug(s"Got the following destination to invalidate $ip")
                invalidateFlowsFor(FlowTagger.tagForDestinationIp(id, ip))
            }
        }
    }

    private def handle(msg: BackChannelMessage): Unit = msg match {
        case m: InvalidateFlows => invalidateRoutedFlows(m)
        case tag: FlowTag => invalidateFlowsFor(tag)
        case RestartWorkflow(pktCtx, error) => restart(pktCtx, error)
        case m: GeneratedPacket => startWorkflow(generatedPacketContext(m))
        case m: FlowStateBatch => replicator.importFromStorage(m)
    }

    override def process(): Unit = {
        super.process()
        while (backChannel.hasMessages)
            handle(backChannel.poll())
        connTrackStateTable.expireIdleEntries((), invalidateExpiredConnTrackKeys)
        natStateTable.expireIdleEntries((), invalidateExpiredNatKeys)
        natLeaser.obliterateUnusedBlocks()
        traceStateTable.expireIdleEntries()
        arpBroker.process()
        waitingRoom.doExpirations(giveUpWorkflow)
    }

    protected def packetContext(packet: Packet): PacketContext =
        initialize(cookieGen.next, packet, packet.getMatch, null, null)

    protected def generatedPacketContext(p: GeneratedPacket) = {
        log.debug(s"Executing generated packet $p")
        val fmatch = FlowMatches.fromEthernetPacket(p.eth)
        val packet = new Packet(p.eth, fmatch, p.eth.length())
        p match {
            case GeneratedLogicalPacket(portId, _, _) =>
                initialize(p.cookie, packet, fmatch, portId, null)
            case GeneratedPhysicalPacket(portNo, _, _) =>
                initialize(p.cookie, packet, fmatch, null, portNo)
        }
    }

    private def initialize(cookie: Long, packet: Packet, fmatch: FlowMatch,
                           egressPortId: UUID, egressPortNo: JInteger) = {
        log.debug(s"Creating new PacketContext for cookie $cookie")
        val context = new PacketContext(cookie, packet, fmatch,
                                        egressPortId, egressPortNo)
        context.reset(backChannel, arpBroker)
        context.initialize(connTrackTx, natTx, natLeaser, traceStateTx)
        context.log = PacketTracing.loggerFor(fmatch)
        context
    }

    /**
     * Deal with an incomplete workflow that could not complete because it found
     * a NotYet on the way.
     */
    private def postponeOn(pktCtx: PacketContext, f: Future[_]): Unit = {
        pktCtx.postpone()
        f.onComplete { res =>
            val error = res match {
                case Failure(ex) => ex
                case _ => null
            }
            backChannel.tell(RestartWorkflow(pktCtx, error))
        }(ExecutionContext.callingThread)
        metrics.packetPostponed()
        waitingRoom enter pktCtx
    }

    private def restart(pktCtx: PacketContext, error: Throwable): Unit =
        if (pktCtx.idle) {
            metrics.packetsOnHold.dec()
            pktCtx.log.debug("Restarting workflow")
            MDC.put("cookie", pktCtx.cookieStr)
            if (error eq null) {
                runWorkflow(pktCtx)
            } else {
                handleErrorOn(pktCtx, error)
                waitingRoom leave pktCtx
            }
            MDC.remove("cookie")
            FlowTracingContext.clearContext()
        } // Else the packet may have already been expired and dropped

    private val giveUpWorkflow: PacketContext => Unit = context =>
        if (context.idle)
            drop(context)

    private def drop(context: PacketContext): Unit =
        try {
            MDC.put("cookie", context.cookieStr)
            context.prepareForDrop()
            if (context.ingressed) {
                context.log.debug("Dropping packet")
                addTranslatedFlow(context, FlowExpirationIndexer.ERROR_CONDITION_EXPIRATION)
                handoff(context)
            }
        } catch { case NonFatal(e) =>
            context.log.error("Failed to install drop flow", e)
        } finally {
            MDC.remove("cookie")
            metrics.packetsDropped.mark()
        }

    private def complete(pktCtx: PacketContext, simRes: SimulationResult): Unit = {
        pktCtx.log.debug("Packet processed")
        if (pktCtx.runs > 1)
            waitingRoom leave pktCtx

        handoff(pktCtx)

        if (pktCtx.ingressed) {
            simRes match {
                case StateMessage | FlowCreated | UserspaceFlow =>
                    // Latency will be measured after packet execution
                case _ =>
                    val latency = NanoClock.DEFAULT.tick - pktCtx.packet.startTimeNanos
                    metrics.packetSimulated(latency.toInt)
                    metrics.packetsProcessed.mark()
            }
        }

        meters.recordPacket(pktCtx.packet.packetLen, pktCtx.flowTags)
        flowRecorder.record(pktCtx, simRes)
    }

    private def handoff(context: PacketContext): Unit = {
        val seq = dpChannel.handoff(context)
        if (context.flow ne null) {
            context.flow.sequence = seq
        }
    }

    /**
     * Handles an error in a workflow execution.
     */
    private def handleErrorOn(pktCtx: PacketContext, ex: Throwable): Unit = {
        ex match {
            case ArpTimeoutException(router, ip) =>
                pktCtx.log.debug(s"ARP timeout at router $router for address $ip")
            case _: DhcpException =>
            case e: DeviceQueryTimeoutException =>
                pktCtx.log.warn("Timeout while fetching " +
                                s"${e.deviceType} with id ${e.deviceId}")
            case e =>
                pktCtx.log.warn("Exception while processing packet", e)
        }
        drop(pktCtx)
    }

    protected def startWorkflow(context: PacketContext): Unit =
        try {
            MDC.put("cookie", context.cookieStr)
            context.log.debug(s"New cookie for new match ${context.origMatch}")
            runWorkflow(context)
        } finally {
            if (context.ingressed)
                packetOut(1)
            MDC.remove("cookie")
            FlowTracingContext.clearContext()
        }

    protected def runWorkflow(pktCtx: PacketContext): Unit =
        try {
            complete(pktCtx, start(pktCtx))
            flushTransactions()
        } catch {
            case TraceRequiredException =>
                pktCtx.log.debug(
                    s"Enabling trace for $pktCtx with match" +
                        s" ${pktCtx.origMatch}, and rerunning simulation")
                pktCtx.prepareForSimulationWithTracing()
                runWorkflow(pktCtx)
            case NotYetException(f, msg) =>
                pktCtx.log.debug(s"Postponing simulation because: $msg")
                postponeOn(pktCtx, f)
            case NonFatal(ex) =>
                handleErrorOn(pktCtx, ex)
        }

    private def handlePacket(packet: Packet): Unit =
        if (FlowStatePackets.isStateMessage(packet.getMatch)) {
            handleStateMessage(packetContext(packet))
            packetOut(1)
        } else {
            processPacket(packet)
        }

    private def processPacket(packet: Packet): Unit =
        startWorkflow(packetContext(packet))

    private def flushTransactions(): Unit = {
        connTrackTx.flush()
        natTx.flush()
        traceStateTx.flush()
    }

    def start(context: PacketContext): SimulationResult = {
        context.prepareForSimulation()
        context.log.debug(s"Initiating processing, attempt: ${context.runs}")
        if (context.ingressed)
            handlePacketIngress(context)
        else
            handlePacketEgress(context)
    }

    protected def addTranslatedFlow(context: PacketContext,
                                    expiration: Expiration): SimulationResult =
        if (context.packet.getReason == Packet.Reason.FlowActionUserspace) {
            resultLogger.debug("packet came up due to userspace dp action, " +
                               s"match ${context.origMatch}")
            context.flowRemovedCallbacks.runAndClear()
            UserspaceFlow
        } else {
            context.origMatch.propagateSeenFieldsFrom(context.wcmatch)
            if (context.origMatch.userspaceFieldsSeen) {
                context.log.debug("Userspace fields seen; skipping flow creation")
                context.flowRemovedCallbacks.runAndClear()
                UserspaceFlow
            } else {
                addFlow(context, expiration)
                FlowCreated
            }
        }

    private def applyState(context: PacketContext): Unit = {
        context.log.debug("Applying connection state")
        replicator.accumulateNewKeys(context)
        replicator.touchState()
        context.commitStateTransactions()
    }

    private def handlePacketIngress(context: PacketContext): SimulationResult = {
        if (!context.origMatch.isUsed(Field.InputPortNumber)) {
            context.log.error("packet had no inPort number")
            return processSimulationResult(context, ErrorDrop)
        }

        val inPortNo = context.origMatch.getInputPortNumber
        context.flowTags.add(tagForDpPort(inPortNo))

        if (context.origMatch.isFromTunnel) {
            handleFromTunnel(context, inPortNo)
        } else if (resolveVport(context, inPortNo)) {
            val result = handleMetadataIngress(context)

            if (result ne null) {
                processSimulationResult(context, result)
            } else {
                processSimulationResult(context, simulatePacketIn(context))
            }
        } else {
            val result = handleMetadataEgress(context)

            if (result ne null) {
                processSimulationResult(context, result)
            } else {
                processSimulationResult(context, handleBgp(context, inPortNo))
            }
        }
    }

    private def resolveVport(context: PacketContext, inPortNo: Int): Boolean = {
        val inPortId = dpState getVportForDpPortNumber inPortNo
        context.inputPort = inPortId
        inPortId != null
    }

    private def handlePacketEgress(context: PacketContext) = {
        context.log.debug("Handling generated packet")
        if (context.egressPort ne null) {
            processSimulationResult(context, Simulator.simulate(context))
        } else {
            context.addVirtualAction(output(context.egressPortNo))
            processSimulationResult(context, AddVirtualWildcardFlow)
        }
    }

    def processSimulationResult(context: PacketContext,
                                result: SimulationResult): SimulationResult = {
        val res = result match {
            case AddVirtualWildcardFlow =>
                concludeSimulation(context)
            case _ if context.isGenerated =>
                context.flowRemovedCallbacks.runAndClear()
                NoOp
            case NoOp =>
                if (context.containsFlowState)
                    applyState(context)
                context.flowRemovedCallbacks.runAndClear()
                NoOp
            case ErrorDrop =>
                context.flowRemovedCallbacks.runAndClear()
                context.clearFlowTags()
                addTranslatedFlow(context, FlowExpirationIndexer.ERROR_CONDITION_EXPIRATION)
            case ShortDrop =>
                context.clearFlowTags()
                if (context.containsFlowState)
                    applyState(context)
                addTranslatedFlow(context, FlowExpirationIndexer.ERROR_CONDITION_EXPIRATION)
            case Drop =>
                if (context.containsFlowState)
                    applyState(context)
                addTranslatedFlow(context, FlowExpirationIndexer.FLOW_EXPIRATION)
        }
        resultLogger.debug(s"Simulation finished with result $res: " +
                           s"match ${context.origMatch}, flow actions " +
                           s"${context.flowActions}, tags ${context.flowTags}")
        res
    }

    private def concludeSimulation(context: PacketContext): SimulationResult = {
        translateActions(context)
        if (context.ingressed) {
            val expiration =
                if (context.containsFlowState) {
                    applyState(context)
                    FlowExpirationIndexer.STATEFUL_FLOW_EXPIRATION
                } else {
                    FlowExpirationIndexer.FLOW_EXPIRATION
                }
            addTranslatedFlow(context, expiration)
        } else {
            // Generated packets are return packets, so we don't apply flow state
            context.flowRemovedCallbacks.runAndClear()
            PacketWorkflow.GeneratedPacket
        }
    }

    protected def simulatePacketIn(context: PacketContext): SimulationResult =
        if (handleDHCP(context)) {
            NoOp
        } else {
            Simulator.simulate(context)
        }

    protected def handleStateMessage(context: PacketContext): Unit = {
        context.log.debug("Accepting a state push message")
        replicator.accept(context.ethernet)
    }

    private def handleDHCP(context: PacketContext): Boolean = {
        val fmatch = context.origMatch
        val isDhcp = fmatch.getEtherType == IPv4.ETHERTYPE &&
                     fmatch.getNetworkProto == UDP.PROTOCOL_NUMBER &&
                     fmatch.getSrcPort == 68 && fmatch.getDstPort == 67

        if (!isDhcp)
            return false

        val port = VirtualTopology.tryGet[Port](context.inputPort)
        val dhcp = context.packet.getEthernet.getPayload.getPayload.getPayload.asInstanceOf[DHCP]
        dhcp.getOpCode == DHCP.OPCODE_REQUEST &&
            processDhcp(context, port, dhcp,
                config.dhcpMtu.min(DatapathController.minMtu))
    }

    private def processDhcp(context: PacketContext, inPort: Port,
                            dhcp: DHCP, mtu: Short): Boolean = {
        val srcMac = context.origMatch.getEthSrc
        val optMtu = Option(mtu)
        DhcpImpl(dhcpConfigProvider, inPort, dhcp, srcMac, optMtu, context.log) match {
            case Some(dhcpReply) =>
                context.log.debug(
                    "sending DHCP reply {} to port {}", dhcpReply, inPort.id)
                context.addGeneratedPacket(inPort.id, dhcpReply)
                true
            case None =>
                false
        }
    }
}
