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

import akka.actor._
import akka.event.LoggingReceive
import com.codahale.metrics.Clock
import com.typesafe.scalalogging.Logger
import org.midonet.cluster.DataClient
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.management.PacketTracing
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.{FlowStatePackets, FlowStateReplicator, FlowStateStorage, NatLeaser}
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.{Datapath, FlowMatch, Packet}
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.sdn.state.{FlowStateTable, FlowStateTransaction}
import org.midonet.util.collection.Reducer
import org.midonet.util.concurrent.ExecutionContextOps
import org.slf4j.MDC
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object DeduplicationActor {
    // Messages
    case class HandlePackets(packet: Array[Packet])

    case class DiscardPacket(cookie: Int)

    /* This message is sent by simulations that result in packets being
     * generated that in turn need to be simulated before they can correctly
     * be forwarded. */
    case class EmitGeneratedPacket(egressPort: UUID, eth: Ethernet,
                                   parentCookie: Option[Int] = None)

    case class RestartWorkflow(pktCtx: PacketContext)

    // This class holds a cache of actions we use to apply the result of a
    // simulation to pending packets while that result isn't written into
    // the WildcardFlowTable. After updating the table, the FlowController
    // will place the FlowMatch in the pending ring buffer so the DDA can
    // evict the entry from the cache.
    sealed class ActionsCache(var size: Int = 1024,
                              log: Logger) {
        size = findNextPowerOfTwo(size)
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

        private def findNextPowerOfTwo(value: Int) =
            1 << (32 - Integer.numberOfLeadingZeros(value - 1))
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
            val dpConnPool: DatapathConnectionPool,
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

    def datapathConn(packet: Packet) = dpConnPool.get(packet.getMatch.hashCode)

    var datapath: Datapath = null
    var dpState: DatapathState = null

    implicit val dispatcher = this.context.system.dispatcher
    implicit val system = this.context.system

    protected val suspendedPackets = new JHashMap[FlowMatch, mutable.HashSet[Packet]]

    protected val simulationExpireMillis = 5000L

    private val waitingRoom = new WaitingRoom[PacketContext](
                                        (simulationExpireMillis millis).toNanos)

    protected val actionsCache = new ActionsCache(log = log)

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

    override def receive = LoggingReceive {

        case DatapathReady(dp, state) if null == datapath =>
            datapath = dp
            dpState = state
            replicator = new FlowStateReplicator(connTrackStateTable,
                natStateTable,
                storage,
                dpState,
                FlowController ! _,
                datapath)
            pendingFlowStateBatches foreach (self ! _)
            workflow = new PacketWorkflow(dpState, datapath, clusterDataClient,
                                          dpConnPool, actionsCache, replicator)

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

        // This creates a new PacketWorkflow and
        // executes the simulation method directly.
        case EmitGeneratedPacket(egressPort, ethernet, parentCookie) =>
            startWorkflow(Packet.fromEthernet(ethernet), Right(egressPort))
    }

    // We return collection.Set so we can return an empty immutable set
    // and a non-empty mutable set.
    private def removePendingPacket(flowMatch: FlowMatch): collection.Set[Packet] = {
        val pending = suspendedPackets.remove(flowMatch)
        if (pending ne null) {
            log.debug(s"Remove ${pending.size} pending packet(s)")
            pending
        } else {
            log.debug("No pending packets")
            Set.empty
        }
    }

    protected def packetContext(packet: Packet,
                                cookieOrEgressPort: Either[Int, UUID],
                                parentCookie: Option[Int] = None)
    : PacketContext = {
        log.debug("Creating new PacketContext for {}", cookieOrEgressPort)

        if (cookieOrEgressPort.isRight)
            packet.generateFlowKeysFromPayload()
        val wcMatch = WildcardMatch.fromFlowMatch(packet.getMatch)

        val pktCtx = new PacketContext(cookieOrEgressPort, packet,
                                       parentCookie, wcMatch)
        pktCtx.state.initialize(connTrackTx, natTx, natLeaser)
        pktCtx.log = PacketTracing.loggerFor(wcMatch)
        pktCtx
    }

    /**
     * Deal with an incomplete workflow that could not complete because it found
     * a NotYet on the way.
     */
    private def postponeOn(pktCtx: PacketContext, f: Future[_]) {
        pktCtx.postpone()
        val flowMatch = pktCtx.packet.getMatch
        suspendedPackets.put(flowMatch, mutable.HashSet())
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
                log.error("Failed to drop flow", e)
        } finally {
            val dropped = if (pktCtx.ingressed) {
                               removePendingPacket(pktCtx.packet.getMatch).size
                          } else {
                               0
                          }
            metrics.packetsDropped.mark(dropped + 1)
        }

    /**
     * Deal with a completed workflow
     */
    private def complete(pktCtx: PacketContext, path: PipelinePath): Unit = {
        log.debug("Packet processed")
        if (pktCtx.runs > 1)
            waitingRoom leave pktCtx
        pktCtx.cookieOrEgressPort match {
            case Left(cookie) =>
                applyFlow(cookie, pktCtx)
                val latency = (Clock.defaultClock().getTick -
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
            case _ => // do nothing
        }
    }

    private def applyFlow(cookie: Int, pktCtx: PacketContext): Unit = {
        val flowMatch = pktCtx.packet.getMatch
        val actions = actionsCache.actions.get(flowMatch)
        val pendingPackets = removePendingPacket(flowMatch)
        val numPendingPackets = pendingPackets.size
        if (numPendingPackets > 0) {
            // Send all pended packets with the same action list (unless
            // the action list is empty, which is equivalent to dropping)
            if (actions.isEmpty) {
                metrics.packetsProcessed.mark(numPendingPackets)
            } else {
                log.debug(s"Sending ${pendingPackets.size} pended packets")
                pendingPackets foreach (executePacket(_, actions))
                metrics.pendedPackets.dec(numPendingPackets)
            }
        }

        if (!pktCtx.isStateMessage && actions.isEmpty) {
            system.eventStream.publish(DiscardPacket(cookie))
        }
    }

    /**
     * Handles an error in a workflow execution.
     */
    private def handleErrorOn(pktCtx: PacketContext, ex: Throwable): Unit = {
        log.warn("Exception while processing packet", ex)
        drop(pktCtx)
    }

    protected def startWorkflow(packet: Packet,
                                cookieOrEgressPort: Either[Int, UUID],
                                parentCookie: Option[Int] = None): Unit =
        try {
            val ctx = packetContext(packet, cookieOrEgressPort, parentCookie)
            MDC.put("cookie", ctx.cookieStr)
            log.debug(s"New cookie for new match ${packet.getMatch}")
            runWorkflow(ctx)
        } catch {
            case ex: Exception =>
                log.error("Unable to execute workflow", ex)
        } finally {
            if (cookieOrEgressPort.isLeft)
                packetOut(1)
            MDC.remove("cookie")
        }

    protected def runWorkflow(pktCtx: PacketContext): Unit =
        try {
            complete(pktCtx, workflow.start(pktCtx))
        } catch {
            case NotYetException(f, msg) =>
                log.debug(s"Postponing simulation because: $msg")
                postponeOn(pktCtx, f)
            case org.midonet.midolman.simulation.FixPortSets => drop(pktCtx)
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
            executePacket(packet, actions)
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

    // No simulation is in progress for the flow match. Create a new
    // cookie and start the packet workflow.
    private def processPacket(packet: Packet): Unit = {
        val newCookie = cookieGen.next
        startWorkflow(packet, Left(newCookie))
    }

    private def executePacket(packet: Packet, actions: JList[FlowAction]) {
        if (actions.isEmpty) {
            return
        }

        try {
            datapathConn(packet).packetsExecute(datapath, packet, actions)
        } catch {
            case e: NetlinkException =>
                log.info("Failed to execute packet: {}", e)
        }
        metrics.packetsProcessed.mark()
    }

    private def flushTransactions(): Unit = {
        connTrackTx.flush()
        natTx.flush()
    }
}
