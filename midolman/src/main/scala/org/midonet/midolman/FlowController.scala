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

import java.nio.ByteBuffer
import java.util.concurrent.{TimeUnit, ConcurrentHashMap => ConcHashMap}
import java.util.{ArrayList, Map => JMap, Set => JSet}
import javax.inject.Inject

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.mutable.{HashMap, MultiMap}
import scala.collection.{mutable, Set => ROSet}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import akka.actor._
import akka.event.LoggingReceive
import com.codahale.metrics.MetricRegistry.name
import com.codahale.metrics.{Gauge, MetricRegistry}
import org.jctools.queues.SpscArrayQueue
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.flows.{FlowEjector, WildcardTablesProvider}
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.metrics.{FlowTablesGauge, FlowTablesMeter}
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.netlink.{BytesUtil, Callback}
import org.midonet.odp.{Datapath, Flow, FlowMatch, OvsProtocol}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.flows._
import org.midonet.util.collection.{ArrayObjectPool, ObjectPool}
import org.midonet.util.concurrent.WakerUpper.Parkable
import org.midonet.util.functors.{Callback0, Callback1}
import rx.Observer

// TODO(guillermo) - move to a scala util pkg in midonet-util
sealed trait EventSearchResult
case object EventSeen extends EventSearchResult
case object EventNotSeen extends EventSearchResult
case object EventSearchWindowMissed extends EventSearchResult

class EventHistory[T](val slots: Int) {
    @volatile private var events = Vector[HistoryEntry]()

    class HistoryEntry(val event: T) {
        val id: Long = youngest + 1
    }

    def youngest: Long = events.headOption match {
        case Some(e) => e.id
        case None => 0
    }

    def oldest: Long = events.lastOption match {
        case Some(e) => e.id
        case None => 0
    }

    def put(event: T): Long = {
        events = new HistoryEntry(event) +: events
        if (events.size > slots)
            events = events dropRight 1
        youngest
    }

    def exists(lastSeen: Long, eventSet: ROSet[T]): EventSearchResult = {
        val frozen = events

        if (frozen.isEmpty || oldest > lastSeen + 1)
            return EventSearchWindowMissed

        var i = 0
        while (frozen.isDefinedAt(i) && frozen(i).id > lastSeen) {
            if (eventSet.contains(frozen(i).event))
                return EventSeen
            i = i+1
        }
        EventNotSeen
    }

    def exists(lastSeen: Long, ev: T): EventSearchResult =  exists(lastSeen, ROSet(ev))
}


object FlowController extends Referenceable {
    override val Name = "FlowController"

    case class AddWildcardFlow(wildFlow: WildcardFlow,
                               dpFlow: Flow,
                               flowRemovalCallbacks: ArrayList[Callback0],
                               tags: ROSet[FlowTag],
                               lastInvalidation: Long = -1,
                               flowMatch: FlowMatch = null,
                               processed: Array[FlowMatch] = null,
                               index: Int = 0)

    case class RemoveWildcardFlow(wMatch: WildcardMatch)

    case class WildcardFlowAdded(f: WildcardFlow)

    case class WildcardFlowRemoved(f: WildcardFlow)

    case class InvalidateFlowsByTag(tag: FlowTag)

    case class FlowAdded(flow: Flow, wcMatch: WildcardMatch)

    case class FlowUpdateCompleted(flow: Flow) // used in test only

    case object CheckFlowExpiration_

    case class FlowMissing_(flowMatch: FlowMatch, flowCallback: Callback1[Flow])

    case class GetFlowSucceeded_(flow: Flow, flowCallback: Callback1[Flow])

    case class GetFlowFailed_(flowCallback: Callback1[Flow])

    case object CheckCompletedRequests

    val MIN_WILDCARD_FLOW_CAPACITY = 4096

    // TODO(guillermo) tune these values
    private val WILD_FLOW_TABLE_CONCURRENCY_LEVEL = 1
    private val WILD_FLOW_TABLE_LOAD_FACTOR = 0.75f
    private val WILD_FLOW_TABLE_INITIAL_CAPACITY = 65536
    private val WILD_FLOW_PARENT_TABLE_INITIAL_CAPACITY = 256

    private val wildcardTables: JMap[JSet[WildcardMatch.Field],
                                        JMap[WildcardMatch, ManagedWildcardFlow]] =
        new ConcHashMap[JSet[WildcardMatch.Field],
                        JMap[WildcardMatch, ManagedWildcardFlow]](
            WILD_FLOW_PARENT_TABLE_INITIAL_CAPACITY,
            WILD_FLOW_TABLE_LOAD_FACTOR,
            WILD_FLOW_TABLE_CONCURRENCY_LEVEL)

    private val wildcardTablesProvider: WildcardTablesProvider =
        new WildcardTablesProvider {
            override def tables = wildcardTables

            override def addTable(pattern: JSet[WildcardMatch.Field]) = {
                var table = wildcardTables.get(pattern)
                if (table == null) {
                    table = new ConcHashMap[WildcardMatch, ManagedWildcardFlow](
                                    WILD_FLOW_TABLE_INITIAL_CAPACITY,
                                    WILD_FLOW_TABLE_LOAD_FACTOR,
                                    WILD_FLOW_TABLE_CONCURRENCY_LEVEL)

                    wildcardTables.put(pattern, table)
                }
                table
            }
        }

    def queryWildcardFlowTable(wildMatch: WildcardMatch)
    : Option[ManagedWildcardFlow] = {
        var wildFlow: ManagedWildcardFlow = null
        wildMatch.doNotTrackSeenFields()
        for (entry <- wildcardTables.entrySet()) {
            val table = entry.getValue
            val pattern = entry.getKey
            val projectedFlowMatch = wildMatch.project(pattern)
            if (projectedFlowMatch != null) {
                val candidate = table.get(projectedFlowMatch)
                if (null != candidate) {
                    if (null == wildFlow)
                        wildFlow = candidate
                    else if (candidate.getPriority < wildFlow.getPriority)
                        wildFlow = candidate
                }
            }
        }
        wildMatch.doTrackSeenFields()
        Option(wildFlow)
    }


    private val invalidationHistory = new EventHistory[FlowTag](1024)

    def isTagSetStillValid(lastSeenInvalidation: Long, tags: ROSet[FlowTag]) = {
        if (lastSeenInvalidation >= 0) {
            invalidationHistory.exists(lastSeenInvalidation, tags) match {
                case EventSearchWindowMissed => tags.isEmpty
                case EventSeen => false
                case EventNotSeen => true
            }
        } else {
            true
        }
    }

    def lastInvalidationEvent = invalidationHistory.youngest

    sealed abstract class FlowOvsCommand[T](completedRequests: SpscArrayQueue[T])
                                           (implicit actorSystem: ActorSystem)
        extends Observer[ByteBuffer] { self: T =>

        var failure: Throwable = _

        def isFailed = failure ne null

        def netlinkErrorCode() =
            failure match {
                case ex: NetlinkException => ex.getErrorCodeEnum
                case _ => NetlinkException.GENERIC_IO_ERROR
            }

        def clear(): Unit =
            failure = null

        def prepareRequest(datapathId: Int, protocol: OvsProtocol): ByteBuffer

        final override def onCompleted(): Unit = {
            completedRequests.offer(this)
            FlowController ! FlowController.CheckCompletedRequests
        }

        final override def onError(e: Throwable): Unit = {
            failure = e
            onCompleted()
        }
    }

    sealed class FlowRemoveCommand(pool: ObjectPool[FlowRemoveCommand],
                                   completedRequests: SpscArrayQueue[FlowRemoveCommand])
                                  (implicit actorSystem: ActorSystem)
        extends FlowOvsCommand[FlowRemoveCommand](completedRequests) {

        private val buf = BytesUtil.instance.allocateDirect(8*1024)
        val flow = new Flow()
        var retries: Int = _
        var flowMatch: FlowMatch = _

        def reset(flowMatch: FlowMatch, retries: Int): Unit = {
            this.flowMatch = flowMatch
            this.retries = retries
        }

        override def clear(): Unit = {
            super.clear()
            flowMatch = null
            pool.offer(this)
        }

        override def prepareRequest(datapathId: Int, protocol: OvsProtocol): ByteBuffer = {
            buf.clear()
            protocol.prepareFlowDelete(datapathId, flowMatch.getKeys, buf)
            buf
        }

        override def onNext(t: ByteBuffer): Unit =
            flow.deserialize(t)
    }
}

class FlowController extends Actor with ActorLogWithoutPath
        with DatapathReadySubscriberActor {
    import DatapathController.DatapathReady
    import FlowController._

    override def logSource = "org.midonet.flow-management"

    implicit val system = this.context.system

    var datapath: Datapath = null

    @Inject
    var midolmanConfig: MidolmanConfig = null

    @Inject
    var datapathConnPool: DatapathConnectionPool = null

    def datapathConnection(flowMatch: FlowMatch) = datapathConnPool.get(flowMatch.hashCode)

    @Inject
    var ejector: FlowEjector = null

    var pooledFlowDeleteCommands: ArrayObjectPool[FlowRemoveCommand] = _
    var completedFlowDeleteCommands: SpscArrayQueue[FlowRemoveCommand] = _

    @Inject
    var metricsRegistry: MetricRegistry = null

    var flowManager: FlowManager = null
    var flowManagerHelper: FlowManagerHelper = null

    val tagToFlows: MultiMap[FlowTag, ManagedWildcardFlow] =
        new HashMap[FlowTag, mutable.Set[ManagedWildcardFlow]]
            with MultiMap[FlowTag, ManagedWildcardFlow]

    var flowExpirationCheckInterval: FiniteDuration = null

    private var wildFlowPool: ObjectPool[ManagedWildcardFlow] = null

    var metrics: FlowTablesMetrics = null

    private[this] implicit def executor: ExecutionContext = context.dispatcher

    override def preStart() {
        super.preStart()
        FlowController.wildcardTables.clear()
        val maxDpFlows = midolmanConfig.getDatapathMaxFlowCount
        val maxWildcardFlows = midolmanConfig.getDatapathMaxWildcardFlowCount match {
            case x if x < MIN_WILDCARD_FLOW_CAPACITY => MIN_WILDCARD_FLOW_CAPACITY
            case y => y
        }
        val idleFlowToleranceInterval = midolmanConfig.getIdleFlowToleranceInterval
        flowExpirationCheckInterval = Duration(midolmanConfig.getFlowExpirationInterval,
            TimeUnit.MILLISECONDS)


        flowManagerHelper = new FlowManagerInfoImpl()
        flowManager = new FlowManager(flowManagerHelper,
            FlowController.wildcardTablesProvider ,maxDpFlows, maxWildcardFlows,
            idleFlowToleranceInterval)

        wildFlowPool = new ArrayObjectPool(maxWildcardFlows, new ManagedWildcardFlow(_))

        metrics = new FlowTablesMetrics(flowManager)

        completedFlowDeleteCommands = new SpscArrayQueue(ejector.maxPendingRequests)
        pooledFlowDeleteCommands = new ArrayObjectPool(ejector.maxPendingRequests,
                                                       new FlowRemoveCommand(_, completedFlowDeleteCommands))
    }

    def receive = LoggingReceive {
        case DatapathReady(dp, dpState) =>
            if (null == datapath) {
                datapath = dp
                // schedule next check for flow expiration after 20 ms and then after
                // every flowExpirationCheckInterval ms
                context.system.scheduler.schedule(20 millis,
                    flowExpirationCheckInterval,
                    self,
                    CheckFlowExpiration_)
            }

        case msg@AddWildcardFlow(wildFlow, dpFlow, callbacks, tags, lastInval,
                                 flowMatch, pendingFlowMatches, index) =>
            context.system.eventStream.publish(msg)
            if (FlowController.isTagSetStillValid(lastInval, tags)) {
                var managedFlow = wildFlowPool.take
                if (managedFlow eq null) {
                    flowManager.evictOldestFlows()
                    managedFlow = wildFlowPool.take
                }
                if (managedFlow eq null) {
                    log.warn("Failed to add wildcard flow, no capacity")
                } else {
                    managedFlow.reset(wildFlow)
                    managedFlow.ref()           // the FlowController's ref
                    if (handleFlowAddedForNewWildcard(managedFlow, dpFlow,
                                                      callbacks, tags)) {
                        context.system.eventStream.publish(WildcardFlowAdded(wildFlow))

                        log.debug(s"Added wildcard flow ${wildFlow.getMatch} " +
                            s"with tags $tags " +
                            s"idleTout=${wildFlow.getIdleExpirationMillis} " +
                            s"hardTout=${wildFlow.getHardExpirationMillis}")

                        metrics.wildFlowsMetric.mark()
                    } else {
                        managedFlow.unref()     // the FlowController's ref
                    }
                    metrics.currentDpFlows = flowManager.getNumDpFlows
                }
            } else {
                log.debug("Skipping obsolete wildcard flow with match {} and tags {}",
                          wildFlow.getMatch, tags)
                if (dpFlow != null) {
                    flowManagerHelper removeFlow dpFlow.getMatch
                    metrics.currentDpFlows = flowManager.getNumDpFlows
                }
                wildFlow.cbExecutor.schedule(callbacks)
            }

            // We assume metrics.wildFlowsMetric.mark() already contains a
            // memory barrier that will ensure the new table entry is visible
            // before the next write.
            if (pendingFlowMatches != null)
                pendingFlowMatches(index) = flowMatch

        case FlowAdded(dpFlow, wcMatch) =>
            handleFlowAddedForExistingWildcard(dpFlow, wcMatch)
            metrics.currentDpFlows = flowManager.getNumDpFlows

        case InvalidateFlowsByTag(tag) =>
            tagToFlows.remove(tag) match {
                case None =>
                    log.debug(s"There are no flows to invalidate for tag $tag")
                case Some(flowSet) =>
                    log.debug(s"There are ${flowSet.size} flows to invalidate for tag $tag")
                    for (wildFlow <- flowSet)
                        removeWildcardFlow(wildFlow)
            }
            invalidationHistory.put(tag)
            metrics.currentDpFlows = flowManager.getNumDpFlows

        case RemoveWildcardFlow(wmatch) =>
            log.debug("Removing wcflow for match {}", wmatch)
            wildcardTables.get(wmatch.getUsedFields) match {
                case null =>
                case table => table.get(wmatch) match {
                    case null =>
                    case wflow => removeWildcardFlow(wflow)
                }
            }
            metrics.currentDpFlows = flowManager.getNumDpFlows

        case CheckFlowExpiration_ =>
            flowManager.checkFlowsExpiration()
            metrics.currentDpFlows = flowManager.getNumDpFlows

        case GetFlowSucceeded_(flow, callback) =>
            log.debug("Retrieved flow from datapath: {}", flow.getMatch)
            context.system.eventStream.publish(FlowUpdateCompleted(flow))
            callback.call(flow)

        case GetFlowFailed_(callback) => callback.call(null)

        case FlowMissing_(flowMatch, callback) =>
            callback.call(null)
            flowManager.flowMissing(flowMatch)
            metrics.currentDpFlows = flowManager.getNumDpFlows

        case CheckCompletedRequests =>
            processRemovedFlows()
    }

    private def removeWildcardFlow(wildFlow: ManagedWildcardFlow) {
        @tailrec
        def tagsCleanup(tags: Array[FlowTag], i: Int = 0) {
            if (tags != null && tags.length > i) {
                tagToFlows.removeBinding(tags(i), wildFlow)
                tagsCleanup(tags, i+1)
            }
        }

        if (flowManager.remove(wildFlow)) {
            tagsCleanup(wildFlow.tags)
            wildFlow.unref() // tags ref
            wildFlow.cbExecutor.schedule(wildFlow.callbacks)
            context.system.eventStream.publish(WildcardFlowRemoved(wildFlow.immutable))
            wildFlow.unref() // FlowController's ref
        }
        metrics.currentDpFlows = flowManager.getNumDpFlows
    }

    private def handleFlowAddedForExistingWildcard(dpFlow: Flow,
                                                   wcMatch: WildcardMatch) {
        FlowController.queryWildcardFlowTable(wcMatch) match {
            case Some(wildFlow) =>
                // the query doesn't miss, we don't care whether the returned
                // wildcard flow is the same that the client has: the dp flow
                // is valid anyway.
                flowManager.add(dpFlow, wildFlow)
                metrics.dpFlowsMetric.mark()
            case None =>
                // The wildFlow timed out or was invalidated in the interval
                // since the client queried for it the first time. We do not
                // re-add the wildcard flow, that would be incorrect if it
                // disappeared due to invalidation. Instead, we remove the dp
                // flow that the client is reporting as added.
                flowManagerHelper removeFlow dpFlow.getMatch
        }
    }

    private def handleFlowAddedForNewWildcard(wildFlow: ManagedWildcardFlow,
                                              dpFlow: Flow,
                                              flowRemovalCallbacks: ArrayList[Callback0],
                                              tags: ROSet[FlowTag]): Boolean = {

        if (!flowManager.add(wildFlow)) {
            log.error("FlowManager failed to install wildcard flow {}", wildFlow)
            wildFlow.cbExecutor.schedule(flowRemovalCallbacks)
            return false
        }

        wildFlow.callbacks = flowRemovalCallbacks
        wildFlow.ref() // tags ref
        if (null != tags) {
            wildFlow.tags = tags.toArray
            for (tag <- tags) {
                tagToFlows.addBinding(tag, wildFlow)
            }
        }

        if (dpFlow != null) {
            flowManager.add(dpFlow, wildFlow)
            metrics.dpFlowsMetric.mark()
        }
        true
    }

    private def processRemovedFlows(): Unit = {
        var req: FlowRemoveCommand = null
        while ({ req = completedFlowDeleteCommands.poll(); req } ne null) {
            if (req.isFailed) {
                flowDeleteFailed(req)
            } else {
                notifyRemoval(req)
            }
        }
    }

    private def flowDeleteFailed(req: FlowRemoveCommand): Unit = {
        log.debug("Got an exception when trying to remove " +
                  s"flow with match ${req.flowMatch}", req.failure)
        req.netlinkErrorCode() match {
            // Success cases, the flow doesn't exist so userspace
            // can take it as a successful remove:
            case ErrorCode.ENODEV => notifyRemoval(req)
            case ErrorCode.ENOENT => notifyRemoval(req)
            case ErrorCode.ENXIO => notifyRemoval(req)
            // Retry cases:
            case ErrorCode.EBUSY => scheduleRetry(req)
            case ErrorCode.EAGAIN => scheduleRetry(req)
            case ErrorCode.EIO => scheduleRetry(req)
            case ErrorCode.EINTR => scheduleRetry(req)
            case ErrorCode.ETIMEOUT => scheduleRetry(req)
            case _ => giveUp(req)
        }
    }

    private def scheduleRetry(req: FlowRemoveCommand): Unit = {
        var retries = req.retries
        if (retries > 0) {
            retries -= 1
            log.debug(s"Scheduling retry of flow deletion with match: ${req.flowMatch}")
            context.system.scheduler.scheduleOnce(1 second) {
                req.reset(req.flowMatch, retries)
                ejector.eject(req)
            }
        } else {
            giveUp(req)
        }
    }

    private def notifyRemoval(req: FlowRemoveCommand): Unit = {
        log.debug(s"DP confirmed removal of flow with match ${req.flowMatch}")
        req.clear()
    }

    private def giveUp(req: FlowRemoveCommand): Unit = {
        log.error(s"Failed to delete flow with match: ${req.flowMatch}", req.failure)
        req.clear()
    }

    sealed class FlowManagerInfoImpl extends FlowManagerHelper with Parkable {

        override def shouldWakeUp() = completedFlowDeleteCommands.size > 0

        def removeFlow(flowMatch: FlowMatch): Unit = {
            metrics.currentDpFlows = flowManager.getNumDpFlows
            var req: FlowRemoveCommand = null
            while ({ req = pooledFlowDeleteCommands.take; req } eq null) {
                park()
                processRemovedFlows()
            }
            req.reset(flowMatch, 10)
            ejector.eject(req)
        }

        def removeWildcardFlow(flow: ManagedWildcardFlow): Unit = {
            FlowController.this.removeWildcardFlow(flow)
        }

        def getFlow(flowMatch: FlowMatch, flowCallback: Callback1[Flow]): Unit = {
            log.debug("requesting flow for flow match: {}", flowMatch)
            val cb = new Callback[Flow] {
                def onError(ex: NetlinkException) {
                    ex.getErrorCodeEnum match {
                        case ErrorCode.ENOENT =>
                            self ! FlowMissing_(flowMatch, flowCallback)
                        case other =>
                            log.error("Got exception when trying to " +
                                      "flowsGet() for " + flowMatch, ex)
                            self ! GetFlowFailed_(flowCallback)
                    }
                }
                def onSuccess(data: Flow) {
                    val msg = if (data != null) {
                        GetFlowSucceeded_(data, flowCallback)
                    } else {
                        log.warn("getFlow() returned a null flow")
                        FlowMissing_(flowMatch, flowCallback)
                    }
                    self ! msg
                }
            }
            datapathConnection(flowMatch).flowsGet(datapath, flowMatch, cb)
        }
    }

    class FlowTablesMetrics(val flowManager: FlowManager) {
        @volatile var currentDpFlows: Long = 0L

        val currentWildFlowsMetric = metricsRegistry.register(name(
                classOf[FlowTablesGauge], "currentWildcardFlows"),
                new Gauge[Long]{
                    override def getValue = flowManager.getNumWildcardFlows
                })

        val currentDpFlowsMetric = metricsRegistry.register(name(
                classOf[FlowTablesGauge], "currentDatapathFlows"),
                new Gauge[Long]{
                    override def getValue = currentDpFlows
                })

        val wildFlowsMetric = metricsRegistry.meter(name(
                classOf[FlowTablesMeter], "wildcardFlowsCreated",
                "wildcardFlows"))

        val dpFlowsMetric = metricsRegistry.meter(name(
                classOf[FlowTablesMeter], "datapathFlowsCreated",
                "datapathFlows"))
    }
}
