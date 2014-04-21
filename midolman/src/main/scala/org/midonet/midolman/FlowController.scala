/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.util.concurrent.TimeUnit
import java.util.concurrent.{ConcurrentHashMap => ConcHashMap}
import java.util.{Set => JSet, Map => JMap}
import javax.inject.Inject
import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.mutable.{HashMap, MultiMap}
import scala.collection.{Set => ROSet, mutable}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import akka.actor._
import akka.event.LoggingReceive
import com.yammer.metrics.core.{Gauge, MetricsRegistry}

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.ErrorHandlingCallback
import org.midonet.midolman.flows.WildcardTablesProvider
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.metrics.FlowTablesGauge
import org.midonet.midolman.monitoring.metrics.FlowTablesMeter
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.netlink.{Callback => NetlinkCallback}
import org.midonet.odp.{Datapath, Flow, FlowMatch}
import org.midonet.sdn.flows.FlowManager
import org.midonet.sdn.flows.FlowManagerHelper
import org.midonet.sdn.flows.ManagedWildcardFlow
import org.midonet.sdn.flows.WildcardFlow
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.util.collection.{ArrayObjectPool, ObjectPool}
import org.midonet.util.functors.{Callback0, Callback1}

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

        if (frozen.isEmpty || oldest > lastSeen)
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
                               flowRemovalCallbacks: Seq[Callback0],
                               tags: ROSet[Any],
                               lastInvalidation: Long = 0,
                               flowMatch: FlowMatch = null,
                               processed: Array[FlowMatch] = null,
                               index: Int = 0)

    case class RemoveWildcardFlow(wMatch: WildcardMatch)

    case class WildcardFlowAdded(f: WildcardFlow)

    case class WildcardFlowRemoved(f: WildcardFlow)

    case class InvalidateFlowsByTag(tag: Any)

    case class FlowAdded(flow: Flow, wcMatch: WildcardMatch)

    case class FlowUpdateCompleted(flow: Flow) // used in test only

    object Internal {

        case object CheckFlowExpiration

        case class FlowRemoved(flow: Flow)

        case class FlowMissing(flowMatch: FlowMatch, flowCallback: Callback1[Flow])

        case class GetFlowSucceeded(flow: Flow, flowCallback: Callback1[Flow])

        case class GetFlowFailed(flowCallback: Callback1[Flow])

    }

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


    private val invalidationHistory = new EventHistory[Any](1024)

    def isTagSetStillValid(lastSeenInvalidation: Long, tags: ROSet[Any]) = {
        if (lastSeenInvalidation > 0) {
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

    def runCallbacks(callbacks: Iterable[Callback0]) {
        val iter = callbacks.iterator
        while (iter.hasNext) {
            iter.next().call()
        }
    }

}


class FlowController extends Actor with ActorLogWithoutPath {

    import FlowController._
    import FlowController.Internal._
    import DatapathController.DatapathReady

    implicit val system = this.context.system

    var datapath: Datapath = null

    @Inject
    var midolmanConfig: MidolmanConfig = null

    @Inject
    var datapathConnPool: DatapathConnectionPool = null

    def datapathConnection(flowMatch: FlowMatch) = datapathConnPool.get(flowMatch.hashCode)

    @Inject
    var metricsRegistry: MetricsRegistry = null

    var flowManager: FlowManager = null
    var flowManagerHelper: FlowManagerHelper = null

    val tagToFlows: MultiMap[Any, ManagedWildcardFlow] =
        new HashMap[Any, mutable.Set[ManagedWildcardFlow]]
            with MultiMap[Any, ManagedWildcardFlow]

    var flowExpirationCheckInterval: FiniteDuration = null

    private var wildFlowPool: ObjectPool[ManagedWildcardFlow] = null

    var metrics: FlowTablesMetrics = null

    private[this] implicit def executor: ExecutionContext = context.dispatcher

    override def preStart() {
        super.preStart()
        FlowController.wildcardTables.clear()
        val maxDpFlows = midolmanConfig.getDatapathMaxFlowCount
        val maxWildcardFlows = midolmanConfig.getDatapathMaxWildcardFlowCount match {
            case x if (x < MIN_WILDCARD_FLOW_CAPACITY) => MIN_WILDCARD_FLOW_CAPACITY
            case y => y
        }
        val idleFlowToleranceInterval = midolmanConfig.getIdleFlowToleranceInterval
        flowExpirationCheckInterval = Duration(midolmanConfig.getFlowExpirationInterval,
            TimeUnit.MILLISECONDS)


        flowManagerHelper = new FlowManagerInfoImpl()
        flowManager = new FlowManager(flowManagerHelper,
            FlowController.wildcardTablesProvider ,maxDpFlows, maxWildcardFlows,
            idleFlowToleranceInterval, context.system.eventStream)

        wildFlowPool = new ArrayObjectPool[ManagedWildcardFlow](maxWildcardFlows) {
            override def allocate = new ManagedWildcardFlow(this)
        }

        metrics = new FlowTablesMetrics(flowManager)
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
                    CheckFlowExpiration)
            }

        case msg@AddWildcardFlow(wildFlow, dpFlow, callbacks, tags, lastInval,
                                 flowMatch, pendingFlowMatches, index) =>
            context.system.eventStream.publish(msg)
            if (FlowController.isTagSetStillValid(lastInval, tags)) {
                wildFlowPool.take.orElse {
                    flowManager.evictOneFlow()
                    wildFlowPool.take
                } match {
                    case None =>
                        log.warning("Failed to add wildcard flow, no capacity")
                    case Some(managedFlow) =>
                        managedFlow.reset(wildFlow)
                        // the FlowController's ref
                        managedFlow.ref()
                        if (handleFlowAddedForNewWildcard(managedFlow, dpFlow,
                                                          callbacks, tags)) {
                            context.system.eventStream.publish(WildcardFlowAdded(wildFlow))
                            log.debug("Added wildcard flow {} with tags {}", wildFlow, tags)
                            metrics.wildFlowsMetric.mark()
                        } else {
                            // the FlowController's ref
                            managedFlow.unref()
                        }
                }
            } else {
                log.debug("Skipping obsolete wildcard flow {} with tags {}",
                          wildFlow.getMatch, tags)
                if (dpFlow != null) {
                    flowManagerHelper.removeFlow(dpFlow)
                    runCallbacks(callbacks)
                }
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
                    log.debug("There are no flows to invalidate for tag {}",
                        tag)
                case Some(flowSet) =>
                    log.debug("There are {} flows to invalidate for tag {}",
                        flowSet.size, tag)
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

        case CheckFlowExpiration =>
            flowManager.checkFlowsExpiration()
            metrics.currentDpFlows = flowManager.getNumDpFlows

        case GetFlowSucceeded(flow, callback) =>
            log.debug("DP confirmed that flow was updated: {}", flow)
            context.system.eventStream.publish(FlowUpdateCompleted(flow))
            callback.call(flow)

        case GetFlowFailed(callback) => callback.call(null)

        case FlowMissing(flowMatch, callback) =>
            callback.call(null)
            if (flowManager.getActionsForDpFlow(flowMatch) != null) {
                log.warning("DP flow was lost, forgetting: {}", flowMatch)
                flowManager.forgetFlow(flowMatch)
            }
            metrics.currentDpFlows = flowManager.getNumDpFlows

        case FlowRemoved(flow) =>
            log.debug("DP confirmed that flow was removed: {}", flow)
            flowManager.removeFlowCompleted(flow)
            metrics.currentDpFlows = flowManager.getNumDpFlows
    }

    private def removeWildcardFlow(wildFlow: ManagedWildcardFlow) {
        @tailrec
        def tagsCleanup(tags: Array[Any], i: Int = 0) {
            if (tags != null && tags.length > i) {
                tagToFlows.removeBinding(tags(i), wildFlow)
                tagsCleanup(tags, i+1)
            }
        }

        log.debug("removeWildcardFlow - Removing flow {}", wildFlow)
        if (flowManager.remove(wildFlow)) {
            log.debug("removeWildcardFlow - cleaning tags and executing {} " +
                      "callbacks", wildFlow.callbacks.size)
            tagsCleanup(wildFlow.tags)
            wildFlow.unref() // tags ref
            if (wildFlow.callbacks != null)
                runCallbacks(wildFlow.callbacks)
            context.system.eventStream.publish(WildcardFlowRemoved(wildFlow.immutable))
            wildFlow.unref() // FlowController's ref
        }
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
                flowManagerHelper.removeFlow(dpFlow)
        }
    }

    private def handleFlowAddedForNewWildcard(wildFlow: ManagedWildcardFlow,
                                              dpFlow: Flow,
                                              flowRemovalCallbacks: Seq[Callback0],
                                              tags: ROSet[Any]): Boolean = {

        if (!flowManager.add(wildFlow)) {
            log.error("FlowManager failed to install wildcard flow {}", wildFlow)
            if (null != flowRemovalCallbacks)
                for (cb <- flowRemovalCallbacks)
                    cb.call()
            return false
        }

        if (null != flowRemovalCallbacks)
            wildFlow.callbacks = flowRemovalCallbacks.toArray
        wildFlow.ref() // tags ref
        if (null != tags) {
            wildFlow.tags = tags.toArray
            for (tag <- tags) {
                tagToFlows.addBinding(tag, wildFlow)
            }
        }

        if (dpFlow != null) {
            log.debug("Binding dpFlow {} to wcFlow {}", dpFlow, wildFlow)
            flowManager.add(dpFlow, wildFlow)
            metrics.dpFlowsMetric.mark()
        }
        true
    }

    class FlowManagerInfoImpl() extends FlowManagerHelper {
        val sched = context.system.scheduler

        private def _removeFlow(flow: Flow, retries: Int) {
            def scheduleRetry() {
                if (retries > 0) {
                    log.info("Scheduling retry of flow deletion with match: {}",
                             flow.getMatch)
                    sched.scheduleOnce(1 second){
                        _removeFlow(flow, retries - 1)
                    }
                } else {
                    log.error("Giving up on deleting flow with match: {}",
                              flow.getMatch)
                }
            }

            datapathConnection(flow.getMatch).flowsDelete(datapath, flow,
                new NetlinkCallback[Flow] {
                    override def onTimeout() {
                        log.warning("Got a timeout when trying to remove " +
                                    "flow with match {}", flow.getMatch)
                        scheduleRetry()
                    }

                    override def onError(ex: NetlinkException) {
                        log.debug("Got an exception {} when trying to remove " +
                                  "flow with match {}", ex, flow.getMatch)
                        ex.getErrorCodeEnum match {
                            // Success cases, the flow doesn't exist so userspace
                            // can take it as a successful remove:
                            case ErrorCode.ENODEV => onSuccess(flow)
                            case ErrorCode.ENOENT => onSuccess(flow)
                            case ErrorCode.ENXIO => onSuccess(flow)
                            // Retry cases.
                            case ErrorCode.EBUSY => scheduleRetry()
                            case ErrorCode.EAGAIN => scheduleRetry()
                            case ErrorCode.EIO => scheduleRetry()
                            case ErrorCode.EINTR => scheduleRetry()
                            // Give up
                            case _ =>
                                log.error("Giving up on deleting flow with "+
                                    "match: {} due to: {}", flow.getMatch, ex)
                        }
                    }

                    def onSuccess(data: Flow) {
                        self ! FlowRemoved(data)
                    }
                })
        }

        def removeFlow(flow: Flow) {
            _removeFlow(flow, 10)
        }

        def removeWildcardFlow(flow: ManagedWildcardFlow) {
            FlowController.this.removeWildcardFlow(flow)
        }

        def getFlow(flowMatch: FlowMatch, flowCallback: Callback1[Flow] ) {
            log.debug("requesting flow for flow match: {}", flowMatch)

                datapathConnection(flowMatch).flowsGet(datapath, flowMatch,
                new ErrorHandlingCallback[Flow] {

                    def handleError(ex: NetlinkException, timeout: Boolean) {
                        if (ex != null && ex.getErrorCodeEnum == ErrorCode.ENOENT) {
                            self ! FlowMissing(flowMatch, flowCallback)
                        } else {
                            log.error("Got an exception {} or timeout {} when " +
                                "trying to flowsGet() for flow match {}",
                                ex, timeout, flowMatch)
                            self ! GetFlowFailed(flowCallback)
                        }
                    }

                    def onSuccess(data: Flow) {
                        self ! (if (data != null) {
                            GetFlowSucceeded(data, flowCallback)
                        } else {
                            log.warning("Unexpected getFlow() result: success, but a null flow")
                            FlowMissing(flowMatch, flowCallback)
                        })
                    }
                })
        }
    }

    class FlowTablesMetrics(val flowManager: FlowManager) {
        @volatile var currentDpFlows: Long = 0L

        val currentWildFlowsMetric = metricsRegistry.newGauge(
                classOf[FlowTablesGauge],
                "currentWildcardFlows",
                new Gauge[Long]{
                    override def value = flowManager.getNumWildcardFlows
                })

        val currentDpFlowsMetric = metricsRegistry.newGauge(
                classOf[FlowTablesGauge],
                "currentDatapathFlows",
                new Gauge[Long]{
                    override def value = currentDpFlows
                })

        val wildFlowsMetric = metricsRegistry.newMeter(
                classOf[FlowTablesMeter], "wildcardFlowsCreated", "wildcardFlows",
                TimeUnit.SECONDS)

        val dpFlowsMetric = metricsRegistry.newMeter(
                classOf[FlowTablesMeter], "datapathFlowsCreated", "datapathFlows",
                TimeUnit.SECONDS)
    }

}
