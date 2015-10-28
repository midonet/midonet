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

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import akka.actor._
import com.typesafe.scalalogging.Logger
import org.midonet.midolman.flows.FlowExpiration.Expiration

import org.slf4j.{LoggerFactory, MDC}

import org.jctools.queues.MpscArrayQueue

import org.midonet.cluster.DataClient
import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.{FlowProcessor, DatapathChannel}
import org.midonet.midolman.flows.{FlowExpiration, FlowInvalidator}
import org.midonet.midolman.logging.{ActorLogWithoutPath, FlowTracingContext}
import org.midonet.midolman.management.PacketTracing
import org.midonet.midolman.topology.{VxLanPortMapper, VirtualTopologyActor}
import org.midonet.midolman.topology.devices.Port
import org.midonet.midolman.monitoring.FlowRecorder
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.routingprotocols.RoutingWorkflow
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.simulation._
import org.midonet.midolman.state.{FlowStatePackets, FlowStateReplicator, FlowStateStorage, NatLeaser}
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.state._
import org.midonet.odp._
import org.midonet.odp.FlowMatch.Field
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger._
import org.midonet.sdn.state.{FlowStateTable, FlowStateTransaction}
import org.midonet.util.collection.Reducer
import org.midonet.util.concurrent._

object PacketWorkflow {
    case class HandlePackets(packet: Array[Packet])
    case class RestartWorkflow(pktCtx: PacketContext, error: Throwable)

    trait SimulationResult
    case object NoOp extends SimulationResult
    case object Drop extends SimulationResult
    case object ErrorDrop extends SimulationResult
    case object ShortDrop extends SimulationResult
    case object SendPacket extends SimulationResult
    case object AddVirtualWildcardFlow extends SimulationResult
    case object StateMessage extends SimulationResult
    case object UserspaceFlow extends SimulationResult
    case object FlowCreated extends SimulationResult
    case object DuplicatedFlow extends SimulationResult
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
    }

    private def handleFromUnderlay(context: PacketContext): SimulationResult = {
        if (context.hasTraceTunnelBit) {
            context.enableTracingOnEgress()
            context.markUserspaceOnly()
        }
        context.log.debug(s"Received packet matching ${context.origMatch}" +
                              " from underlay")

        val tunnelKey = context.wcmatch.getTunnelKey
        dpState.dpPortNumberForTunnelKey(tunnelKey) match {
            case Some(dpPort) =>
                addActionsForTunnelPacket(context, dpPort)
                addTranslatedFlow(context, FlowExpiration.TUNNEL_FLOW_EXPIRATION)
            case None =>
                processSimulationResult(context, ErrorDrop)
        }
    }
}

class PacketWorkflow(
            val id: Int,
            val config: MidolmanConfig,
            val cookieGen: CookieGenerator,
            val clock: NanoClock,
            val dpChannel: DatapathChannel,
            val clusterDataClient: DataClient,
            val flowInvalidator: FlowInvalidator,
            val flowProcessor: FlowProcessor,
            val connTrackStateTable: FlowStateTable[ConnTrackKey, ConnTrackValue],
            val natStateTable: FlowStateTable[NatKey, NatBinding],
            val traceStateTable: FlowStateTable[TraceKey, TraceContext],
            val storage: Future[FlowStateStorage],
            val natLeaser: NatLeaser,
            val metrics: PacketPipelineMetrics,
            val flowRecorder: FlowRecorder,
            val packetOut: Int => Unit)
        extends Actor with ActorLogWithoutPath with Stash with Backchannel
        with UnderlayTrafficHandler with FlowTranslator with RoutingWorkflow
        with FlowController {

    import DatapathController.DatapathReady
    import PacketWorkflow._

    override def logSource = "org.midonet.packet-worker"
    val resultLogger = Logger(LoggerFactory.getLogger("org.midonet.packets.results"))

    var dpState: DatapathState = null
    var datapathId: Int = _

    implicit val dispatcher = this.context.system.dispatcher
    implicit val system = this.context.system

    protected val simulationExpireMillis = 5000L

    private val waitingRoom = new WaitingRoom[PacketContext](
                                        (simulationExpireMillis millis).toNanos)

    private val genPacketEmitter = new PacketEmitter(new MpscArrayQueue(512), self)

    protected val connTrackTx = new FlowStateTransaction(connTrackStateTable)
    protected val natTx = new FlowStateTransaction(natStateTable)
    protected val traceStateTx = new FlowStateTransaction(traceStateTable)
    protected var replicator: FlowStateReplicator = _

    protected val arpBroker = new ArpRequestBroker(genPacketEmitter, config, flowInvalidator,
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

    context.become {
        case DatapathReady(dp, state) =>
            dpState = state
            datapathId = dp.getIndex
            replicator = new FlowStateReplicator(connTrackStateTable,
                                                 natStateTable,
                                                 traceStateTable,
                                                 storage,
                                                 dpState,
                                                 this,
                                                 config.datapath.controlPacketTos)
            context.become(receive)
            system.scheduler.schedule(20 millis, 5 seconds, self, CheckBackchannels)
            unstashAll()
        case _ => stash()
    }

    override def receive = {
        case m: FlowStateBatch =>
            replicator.importFromStorage(m)

        case HandlePackets(packets) =>
            var i = 0
            while (i < packets.length && packets(i) != null) {
                handlePacket(packets(i))
                i += 1
            }
            process()

        case CheckBackchannels =>
            process()

        case RestartWorkflow(pktCtx, error) =>
            if (pktCtx.idle) {
                metrics.packetsOnHold.dec()
                pktCtx.log.debug("Restarting workflow")
                MDC.put("cookie", pktCtx.cookieStr)
                if (error eq null) {
                    runWorkflow(pktCtx)
                    process()
                } else {
                    handleErrorOn(pktCtx, error)
                    waitingRoom leave pktCtx
                }
                MDC.remove("cookie")
                FlowTracingContext.clearContext()
            }
            // Else the packet may have already been expired and dropped
    }

    override def shouldProcess(): Boolean =
        super.shouldProcess() ||
        genPacketEmitter.pendingPackets > 0 ||
        arpBroker.shouldProcess()

    override def process(): Unit = {
        super.process()
        genPacketEmitter.process(runGeneratedPacket)
        connTrackStateTable.expireIdleEntries((), invalidateExpiredConnTrackKeys)
        natStateTable.expireIdleEntries((), invalidateExpiredNatKeys)
        natLeaser.obliterateUnusedBlocks()
        traceStateTable.expireIdleEntries()
        arpBroker.process()
    }

    protected def packetContext(packet: Packet): PacketContext =
        initialize(packet, packet.getMatch, null)

    protected def generatedPacketContext(egressPort: UUID, eth: Ethernet) = {
        val fmatch = FlowMatches.fromEthernetPacket(eth)
        val packet = new Packet(eth, fmatch, eth.length())
        initialize(packet, fmatch, egressPort)
    }

    private def initialize(packet: Packet, fmatch: FlowMatch, egressPort: UUID) = {
        val cookie = cookieGen.next
        log.debug(s"Creating new PacketContext for cookie $cookie")
        val context = new PacketContext(cookie, packet, fmatch, egressPort)
        context.reset(genPacketEmitter, arpBroker)
        context.initialize(connTrackTx, natTx, natLeaser, traceStateTx)
        context.log = PacketTracing.loggerFor(fmatch)
        context
    }

    /**
     * Deal with an incomplete workflow that could not complete because it found
     * a NotYet on the way.
     */
    private def postponeOn(pktCtx: PacketContext, f: Future[_]) {
        pktCtx.postpone()
        f.onComplete {
            case Success(_) =>
                self ! RestartWorkflow(pktCtx, null)
            case Failure(ex) =>
                self ! RestartWorkflow(pktCtx, ex)
        }(ExecutionContext.callingThread)
        metrics.packetPostponed()
        giveUpWorkflows(waitingRoom enter pktCtx)
    }

    private def giveUpWorkflows(pktCtxs: IndexedSeq[PacketContext]) {
        var i = 0
        while (i < pktCtxs.size) {
            val pktCtx = pktCtxs(i)
            if (pktCtx.idle) {
                drop(pktCtx)
            } else {
                log.warn(s"Pending ${pktCtx.cookieStr} was scheduled for " +
                         "cleanup but was not idle")
            }
            i += 1
        }
    }

    private def drop(context: PacketContext): Unit =
        try {
            MDC.put("cookie", context.cookieStr)
            context.prepareForDrop()
            if (context.ingressed) {
                context.prepareForDrop()
                addTranslatedFlow(context, FlowExpiration.ERROR_CONDITION_EXPIRATION)
                context.log.debug("Dropping packet")
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
        if (pktCtx.ingressed) {
            val latency = NanoClock.DEFAULT.tick - pktCtx.packet.startTimeNanos
            metrics.packetsProcessed.mark()
            simRes match {
                case StateMessage =>
                case _ => metrics.packetSimulated(latency.toInt)
            }
        }

        meters.recordPacket(pktCtx.packet.packetLen, pktCtx.flowTags)
        flowRecorder.record(pktCtx, simRes)
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

    private val runGeneratedPacket = (p: GeneratedPacket) => {
        log.debug(s"Executing generated packet $p")
        startWorkflow(generatedPacketContext(p.egressPort, p.eth))
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

    def logResultNewFlow(msg: String, context: PacketContext): Unit = {
        resultLogger.debug(s"$msg: match ${context.origMatch}, actions " +
                           s"${context.flowActions}, tags ${context.flowTags}")
    }

    def runSimulation(context: PacketContext): SimulationResult =
        new Coordinator(context).simulate()

    protected def addTranslatedFlow(context: PacketContext,
                                    expiration: Expiration): SimulationResult =
        if (context.packet.getReason == Packet.Reason.FlowActionUserspace) {
            resultLogger.debug("packet came up due to userspace dp action, " +
                               s"match ${context.origMatch}")
            context.flowRemovedCallbacks.runAndClear()
            UserspaceFlow
        } else {
            if (!context.isDrop) {
                applyState(context)
            }
            dpChannel.executePacket(context.packet, context.packetActions)
            handleFlow(context, expiration)
        }

    private def handleFlow(context: PacketContext,
                           expiration: Expiration): SimulationResult = {
        context.origMatch.propagateSeenFieldsFrom(context.wcmatch)
        if (context.origMatch.userspaceFieldsSeen) {
            context.log.debug("Userspace fields seen; skipping flow creation")
            context.flowRemovedCallbacks.runAndClear()
            UserspaceFlow
        } else {
            val flow = addFlow(context,expiration)
            if (flow ne null) {
                val dpFlow = new Flow(context.origMatch, context.flowActions)
                logResultNewFlow("Will create flow", context)
                context.log.debug(s"Creating flow $dpFlow")
                flow.sequence = dpChannel.createFlow(dpFlow)
                FlowCreated
            } else {
                DuplicatedFlow
            }
        }
    }

    def applyState(context: PacketContext): Unit = {
        context.log.debug("Applying connection state")
        val traceInfo = if (context.tracingEnabled) {
            Some((context.traceKeyForEgress,context.traceContext))
        } else {
            None
        }
        replicator.accumulateNewKeys(context.conntrackTx,
                                     context.natTx,
                                     traceInfo,
                                     context.inputPort,
                                     context.outPorts,
                                     context.flowTags,
                                     context.flowRemovedCallbacks)
        replicator.pushState(dpChannel)
        context.conntrackTx.commit()
        context.natTx.commit()
    }

    private def handlePacketIngress(context: PacketContext): SimulationResult = {
        if (!context.origMatch.isUsed(Field.InputPortNumber)) {
            context.log.error("packet had no inPort number")
            processSimulationResult(context, ErrorDrop)
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
                translateActions(context)
                val expiration =
                    if (context.containsFlowState)
                        FlowExpiration.STATEFUL_FLOW_EXPIRATION
                    else
                        FlowExpiration.FLOW_EXPIRATION
                addTranslatedFlow(context, expiration)
            case SendPacket =>
                context.flowRemovedCallbacks.runAndClear()
                sendPacket(context)
                PacketWorkflow.GeneratedPacket
            case NoOp =>
                if (context.containsFlowState)
                    applyState(context)
                context.flowRemovedCallbacks.runAndClear()
                resultLogger.debug(s"no-op for match ${context.origMatch} " +
                                   s"tags ${context.flowTags}")
                NoOp
            case ErrorDrop =>
                context.flowRemovedCallbacks.runAndClear()
                context.clearFlowTags()
                addTranslatedFlow(context, FlowExpiration.ERROR_CONDITION_EXPIRATION)
            case ShortDrop =>
                context.clearFlowTags()
                if (context.containsFlowState)
                    applyState(context)
                addTranslatedFlow(context, FlowExpiration.ERROR_CONDITION_EXPIRATION)
            case Drop =>
                if (context.containsFlowState)
                    applyState(context)
                addTranslatedFlow(context, FlowExpiration.FLOW_EXPIRATION)
        }
    }

    protected def simulatePacketIn(context: PacketContext): SimulationResult =
        if (handleDHCP(context)) {
            NoOp
        } else {
            runSimulation(context)
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

        val port = VirtualTopologyActor.tryAsk[Port](context.inputPort)
        val dhcp = context.packet.getEthernet.getPayload.getPayload.getPayload.asInstanceOf[DHCP]
        dhcp.getOpCode == DHCP.OPCODE_REQUEST &&
            processDhcp(context, port, dhcp,
                config.dhcpMtu.min(DatapathController.minMtu))
    }

    private def processDhcp(context: PacketContext, inPort: Port,
                            dhcp: DHCP, mtu: Short): Boolean = {
        val srcMac = context.origMatch.getEthSrc
        val optMtu = Option(mtu)
        DhcpImpl(clusterDataClient, inPort, dhcp, srcMac, optMtu, context.log) match {
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
