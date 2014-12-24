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

import java.util.{UUID, HashMap => JHashMap, List => JList}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import akka.actor._
import com.typesafe.scalalogging.Logger
import org.jctools.queues.MpscArrayQueue
import org.midonet.Util
import org.midonet.cluster.DataClient
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.management.PacketTracing
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.simulation._
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.{FlowStatePackets, FlowStateReplicator, FlowStateStorage, NatLeaser}
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.sdn.state.{FlowStateTable, FlowStateTransaction}
import org.midonet.util.collection.Reducer
import org.midonet.util.concurrent._
import org.slf4j.MDC

object DeduplicationActor {
    // Messages
    case class HandlePackets(packet: Array[Packet])

    case class DiscardPacket(cookie: Int)

    case class RestartWorkflow(pktCtx: PacketContext)

    // This class holds a cache of actions we use to apply the result of a
    // simulation to pending packets while that result isn't written into
    // the WildcardFlowTable. After updating the table, the FlowController
    // will place the FlowMatch in the pending ring buffer so the DDA can
    // evict the entry from the cache.
    sealed class ActionsCache(var size: Int = 1024, cbExecutor: CallbackExecutor,
                              log: Logger) {
        size = Util.findNextPositivePowerOfTwo(size)
        private val mask = size - 1
        val actions = new JHashMap[FlowMatch, JList[FlowAction]]()
        val pending = new Array[FlowMatch](size)
        var free = 0L
        var expecting = 0L

        def clearProcessedFlowMatches(): Int = {
            var cleared = 0
            while ((free - expecting) > 0) {
                val idx = index(expecting)
                val flowMatch = pending(idx)
                if (flowMatch == null)
                    return cleared

                actions.remove(flowMatch)
                pending(idx) = null
                expecting += 1
                cleared += 1
            }
            cleared
        }

        def getSlot(): Int = {
            val res = free
            if (res - expecting == size) {
                log.debug("Waiting for the FlowController to catch up")
                var retries = 200
                while (clearProcessedFlowMatches() == 0) {
                    // While we wait on the FlowController, we must avoid the
                    // deadlock that would ensue if the FlowController were to
                    // begin waiting on us due to lots of flows being invalidated.
                    cbExecutor.run()
                    if (retries > 100) {
                        retries -= 1
                    } else if (retries > 0) {
                        retries -= 1
                        Thread.`yield`()
                    } else {
                        Thread.sleep(0)
                    }
                }
                log.debug("The FlowController has caught up")
            }
            free += 1
            index(res)
        }

        private def index(x: Long): Int = (x & mask).asInstanceOf[Int]
    }
}

class CookieGenerator(val start: Int, val increment: Int) {
    private var nextCookie = start

    def next: Int = {
        val ret = nextCookie
        nextCookie += increment
        ret
    }
}

class DeduplicationActor(
            val cookieGen: CookieGenerator,
            val dpChannel: DatapathChannel,
            val clusterDataClient: DataClient,
            val connTrackStateTable: FlowStateTable[ConnTrackKey, ConnTrackValue],
            val natStateTable: FlowStateTable[NatKey, NatBinding],
            val storage: FlowStateStorage,
            val natLeaser: NatLeaser,
            val metrics: PacketPipelineMetrics,
            val packetOut: Int => Unit)
            extends Actor with ActorLogWithoutPath {

    import org.midonet.midolman.DatapathController.DatapathReady
    import org.midonet.midolman.DeduplicationActor._
    import org.midonet.midolman.PacketWorkflow._

    override def logSource = "org.midonet.packet-worker"

    var dpState: DatapathState = null

    implicit val dispatcher = this.context.system.dispatcher
    implicit val system = this.context.system

    protected val suspendedPackets = new JHashMap[FlowMatch, mutable.HashSet[Packet]]

    protected val simulationExpireMillis = 5000L

    private val waitingRoom = new WaitingRoom[PacketContext](
                                        (simulationExpireMillis millis).toNanos)

    private val cbExecutor = new CallbackExecutor(2048, self)
    private val genPacketEmitter = new PacketEmitter(new MpscArrayQueue(512), self)
    protected val actionsCache = new ActionsCache(cbExecutor = cbExecutor, log = log)

    protected val connTrackTx = new FlowStateTransaction(connTrackStateTable)
    protected val natTx = new FlowStateTransaction(natStateTable)
    protected var replicator: FlowStateReplicator = _

    protected var workflow: PacketHandler = _

    private var pendingFlowStateBatches = List[FlowStateBatch]()

    private val invalidateExpiredConnTrackKeys =
        new Reducer[ConnTrackKey, ConnTrackValue, Unit]() {
            override def apply(u: Unit, k: ConnTrackKey, v: ConnTrackValue) {
                FlowController ! InvalidateFlowsByTag(k)
            }
        }

    private val invalidateExpiredNatKeys =
        new Reducer[NatKey, NatBinding, Unit]() {
            override def apply(u: Unit, k: NatKey, v: NatBinding): Unit = {
                FlowController ! InvalidateFlowsByTag(k)
            }
        }

    override def receive = {

        case DatapathReady(dp, state) if null == dpState =>
            dpState = state
            replicator = new FlowStateReplicator(connTrackStateTable,
                                                 natStateTable,
                                                 storage,
                                                 dpState,
                                                 FlowController ! _)
            pendingFlowStateBatches foreach (self ! _)
            workflow = new PacketWorkflow(dpState, dp, clusterDataClient,
                                          dpChannel, actionsCache, replicator)

        case m: FlowStateBatch =>
            if (replicator ne null)
                replicator.importFromStorage(m)
            else
                pendingFlowStateBatches ::= m

        case HandlePackets(packets) =>
            actionsCache.clearProcessedFlowMatches()

            connTrackStateTable.expireIdleEntries((), invalidateExpiredConnTrackKeys)
            natStateTable.expireIdleEntries((), invalidateExpiredNatKeys)
            natLeaser.obliterateUnusedBlocks()

            var i = 0
            while (i < packets.length && packets(i) != null) {
                handlePacket(packets(i))
                i += 1
            }

            cbExecutor.run()
            genPacketEmitter.process(runGeneratedPacket)

        case CheckBackchannels =>
            cbExecutor.run()
            genPacketEmitter.process(runGeneratedPacket)

        case RestartWorkflow(pktCtx) =>
            MDC.put("cookie", pktCtx.cookieStr)
            if (pktCtx.idle) {
                metrics.packetsOnHold.dec()
                pktCtx.log.debug("Restarting workflow")
                runWorkflow(pktCtx)
            } else {
                pktCtx.log.warn("Tried to restart a non-idle PacketContext")
                drop(pktCtx)
            }
            MDC.remove("cookie")
    }

    // We return collection.Set so we can return an empty immutable set
    // and a non-empty mutable set.
    private def removeSuspendedPackets(flowMatch: FlowMatch): collection.Set[Packet] = {
        val pending = suspendedPackets.remove(flowMatch)
        if (pending ne null) {
            log.debug(s"Removing ${pending.size} suspended packet(s)")
            pending
        } else {
            Set.empty
        }
    }

    protected def packetContext(packet: Packet): PacketContext =
        initialize(packet, null)

    protected def generatedPacketContext(egressPort: UUID, eth: Ethernet) = {
        val packet = new Packet(eth)
        packet.generateFlowKeysFromPayload()
        initialize(packet, egressPort)
    }

    private def initialize(packet: Packet, egressPort: UUID) = {
        val cookie = cookieGen.next
        log.debug(s"Creating new PacketContext for cookie $cookie")
        val wcMatch = WildcardMatch.fromFlowMatch(packet.getMatch)
        val context = new PacketContext(cookie, packet, wcMatch, egressPort)
        context.reset(cbExecutor, genPacketEmitter)
        context.state.initialize(connTrackTx, natTx, natLeaser)
        context.log = PacketTracing.loggerFor(wcMatch)
        context
    }

    /**
     * Deal with an incomplete workflow that could not complete because it found
     * a NotYet on the way.
     */
    private def postponeOn(pktCtx: PacketContext, f: Future[_]) {
        pktCtx.postpone()
        val flowMatch = pktCtx.packet.getMatch
        if (!suspendedPackets.containsKey(flowMatch)) {
            suspendedPackets.put(flowMatch, mutable.HashSet())
        }
        f.onComplete {
            case Success(_) =>
                self ! RestartWorkflow(pktCtx)
            case Failure(ex) =>
                handleErrorOn(pktCtx, ex)
        }(ExecutionContext.callingThread)
        metrics.packetPostponed()
        giveUpWorkflows(waitingRoom enter pktCtx)
    }

    private def giveUpWorkflows(pktCtxs: IndexedSeq[PacketContext]) {
        var i = 0
        while (i < pktCtxs.size) {
            val pktCtx = pktCtxs(i)
            if (!pktCtx.isStateMessage) {
                if (pktCtx.idle)
                    drop(pktCtx)
                else
                    log.warn("Pending {} was scheduled for cleanup " +
                                "but was not idle", pktCtx.cookieStr)
            }
            i += 1
        }
    }

    private def drop(pktCtx: PacketContext): Unit =
        try {
            workflow.drop(pktCtx)
        } catch {
            case e: Exception =>
                pktCtx.log.error("Failed to drop flow", e)
        } finally {
            val dropped = removeSuspendedPackets(pktCtx.packet.getMatch).size
            metrics.packetsDropped.mark(dropped + 1)
        }

    /**
     * Deal with a completed workflow
     */
    private def complete(pktCtx: PacketContext, path: PipelinePath): Unit = {
        pktCtx.log.debug("Packet processed")
        if (pktCtx.runs > 1)
            waitingRoom leave pktCtx
        if (pktCtx.ingressed) {
            applyFlow(pktCtx)
            val latency = (NanoClock.DEFAULT.tick -
                           pktCtx.packet.startTimeNanos).toInt
            metrics.packetsProcessed.mark()
            path match {
                case WildcardTableHit =>
                    metrics.wildcardTableHit(latency)
                case PacketToPortSet =>
                    metrics.packetToPortSet(latency)
                case Simulation =>
                    metrics.packetSimulated(latency)
                case _ =>
            }
        }
    }

    private def applyFlow(pktCtx: PacketContext): Unit = {
        val flowMatch = pktCtx.packet.getMatch
        val actions = actionsCache.actions.get(flowMatch)
        val suspendedPackets = removeSuspendedPackets(flowMatch)
        val numSuspendedPackets = suspendedPackets.size
        if (numSuspendedPackets > 0) {
            log.debug(s"Sending ${suspendedPackets.size} pended packets")
            suspendedPackets foreach (dpChannel.executePacket(_, actions))
            metrics.packetsProcessed.mark(numSuspendedPackets)
            metrics.pendedPackets.dec(numSuspendedPackets)
        }

        if (!pktCtx.isStateMessage && actions.isEmpty) {
            system.eventStream.publish(DiscardPacket(pktCtx.cookie))
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
            case _ =>
                pktCtx.log.warn("Exception while processing packet", ex)
        }
        drop(pktCtx)
    }

    protected def startWorkflow(context: PacketContext): Unit =
        try {
            MDC.put("cookie", context.cookieStr)
            log.debug(s"New cookie for new match ${context.origMatch}")
            runWorkflow(context)
        } catch {
            case ex: Exception =>
                log.error("Unable to execute workflow", ex)
        } finally {
            if (context.ingressed)
                packetOut(1)
            MDC.remove("cookie")
        }

    protected def runWorkflow(pktCtx: PacketContext): Unit =
        try {
            complete(pktCtx, workflow.start(pktCtx))
        } catch {
            case NotYetException(f, msg) =>
                pktCtx.log.debug(s"Postponing simulation because: $msg")
                postponeOn(pktCtx, f)
            case ex: Exception => handleErrorOn(pktCtx, ex)
        } finally {
            flushTransactions()
        }

    private def handlePacket(packet: Packet): Unit = {
        val flowMatch = packet.getMatch
        val actions = actionsCache.actions.get(flowMatch)
        if (actions != null) {
            log.debug("Got actions from the cache {} for match {}",
                       actions, flowMatch)
            dpChannel.executePacket(packet, actions)
            metrics.packetsProcessed.mark()
            packetOut(1)
        } else if (FlowStatePackets.isStateMessage(packet)) {
            processPacket(packet)
        } else suspendedPackets.get(flowMatch) match {
            case null =>
                processPacket(packet)
            case packets =>
                log.debug("A matching packet is already being handled")
                packets.add(packet)
                packetOut(1)
                giveUpWorkflows(waitingRoom.doExpirations())
        }
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
    }
}
