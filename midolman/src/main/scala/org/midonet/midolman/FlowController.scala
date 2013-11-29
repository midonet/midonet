// Copyright 2012 Midokura Inc.

package org.midonet.midolman

import scala.annotation.tailrec
import akka.actor._
import akka.util.duration._
import akka.util.Duration
import akka.event.LoggingReceive
import collection.JavaConversions._
import collection.{Set => ROSet, mutable}
import collection.mutable.{HashMap, MultiMap}
import java.util.concurrent.{ConcurrentHashMap => ConcHashMap,
                             TimeUnit}
import java.util.{Set => JSet, Map => JMap}
import javax.inject.Inject

import com.yammer.metrics.core.{Gauge, MetricsRegistry}

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.ErrorHandlingCallback
import org.midonet.midolman.flows.WildcardTablesProvider
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.metrics.FlowTablesMeter
import org.midonet.midolman.monitoring.metrics.FlowTablesGauge
import org.midonet.netlink.{Callback => NetlinkCallback}
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.odp.{Datapath, Flow, FlowMatch}
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.util.functors.{Callback0, Callback1}
import org.midonet.sdn.flows.{FlowManagerHelper, ManagedWildcardFlow,
                              WildcardMatch, FlowManager, WildcardFlow}
import org.midonet.util.collection.{ArrayObjectPool, ObjectPool}

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
                               flow: Option[Flow],
                               flowRemovalCallbacks: ROSet[Callback0],
                               tags: ROSet[Any],
                               lastInvalidation: Long = 0)

    case class FlowAdded(flow: Flow)

    case class RemoveWildcardFlow(wMatch: WildcardMatch)

    case class InvalidateFlowsByTag(tag: Any)

    case class CheckFlowExpiration()

    case class WildcardFlowAdded(f: WildcardFlow)

    case class WildcardFlowRemoved(f: WildcardFlow)

    case class FlowUpdateCompleted(flow: Flow)

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

    def queryWildcardFlowTable(flowMatch: FlowMatch): Option[ManagedWildcardFlow] = {
        var wildFlow: ManagedWildcardFlow = null
        val wildMatch = WildcardMatch.fromFlowMatch(flowMatch)

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
        while (iter.hasNext()) {
            iter.next().call()
        }
    }

}


class FlowController extends Actor with ActorLogWithoutPath {

    import FlowController._

    var datapath: Datapath = null

    @Inject
    var midolmanConfig: MidolmanConfig = null

    @Inject
    var datapathConnection: OvsDatapathConnection = null

    @Inject
    var metricsRegistry: MetricsRegistry = null

    var flowManager: FlowManager = null
    var flowManagerHelper: FlowManagerHelper = null

    val tagToFlows: MultiMap[Any, ManagedWildcardFlow] =
        new HashMap[Any, mutable.Set[ManagedWildcardFlow]]
            with MultiMap[Any, ManagedWildcardFlow]

    var flowExpirationCheckInterval: Duration = null

    private var wildFlowPool: ObjectPool[ManagedWildcardFlow] = null

    var metrics: FlowTablesMetrics = null

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
        case DatapathController.DatapathReady(dp, dpState) =>
            if (null == datapath) {
                datapath = dp
                // schedule next check for flow expiration after 20 ms and then after
                // every flowExpirationCheckInterval ms
                context.system.scheduler.schedule(Duration(20, TimeUnit.MILLISECONDS),
                    flowExpirationCheckInterval,
                    self,
                    CheckFlowExpiration())
            }

        case AddWildcardFlow(wildFlow, flowOption, callbacks, tags, lastInval) =>
            context.system.eventStream.publish(AddWildcardFlow(wildFlow,flowOption,callbacks,tags,lastInval))
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
                        if (handleFlowAddedForNewWildcard(managedFlow, flowOption, callbacks, tags)) {
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
                flowOption match {
                    case Some(flow) =>
                        flowManagerHelper.removeFlow(flow)
                        runCallbacks(callbacks)
                    case None =>
                }
            }

        case FlowAdded(dpFlow) =>
            handleFlowAddedForExistingWildcard(dpFlow)
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

        case CheckFlowExpiration() =>
            flowManager.checkFlowsExpiration()
            metrics.currentDpFlows = flowManager.getNumDpFlows

        case getFlowSucceded(flow, callback) =>
            log.debug("DP confirmed that flow was updated: {}", flow)
            context.system.eventStream.publish(new FlowUpdateCompleted(flow))
            callback.call(flow)

        case getFlowOnError(callback) => callback.call(null)

        case flowMissing(flowMatch, callback) =>
            callback.call(null)
            Option(flowManager.getActionsForDpFlow(flowMatch)).foreach {
                case actions =>
                    if (flowMatch.isUserSpaceOnly) {
                        log.warning("DP flow was lost, but flowMatch is for" +
                            "userspace only, this should not be happening",
                            flowMatch)
                    } else {
                        log.warning("DP flow was lost, forgetting: {}", flowMatch)
                        flowManager.forgetFlow(flowMatch)
                    }
            }
            metrics.currentDpFlows = flowManager.getNumDpFlows

        case flowRemoved(flow) =>
            log.debug("DP confirmed that flow was removed: {}", flow)
            flowManager.removeFlowCompleted(flow)
            metrics.currentDpFlows = flowManager.getNumDpFlows

    }

    case class flowMissing(flowMatch: FlowMatch, flowCallback: Callback1[Flow])
    case class getFlowSucceded(flow: Flow, flowCallback: Callback1[Flow])
    case class getFlowOnError(flowCallback: Callback1[Flow])
    case class flowRemoved(flow: Flow)

    private def removeWildcardFlow(wildFlow: ManagedWildcardFlow) {
        @tailrec
        def tagsCleanup(tags: Array[Any], i: Int = 0) {
            if (tags != null && tags.length > i) {
                tagToFlows.removeBinding(tags(i), wildFlow)
                tagsCleanup(tags, i+1)
            }
        }

        log.info("removeWildcardFlow - Removing flow {}", wildFlow)
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

    private def handleFlowAddedForExistingWildcard(dpFlow: Flow) {
        FlowController.queryWildcardFlowTable(dpFlow.getMatch) match {
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

    private def handleFlowAddedForNewWildcard(wildFlow: ManagedWildcardFlow, flow: Option[Flow],
                                              flowRemovalCallbacks: ROSet[Callback0],
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

        flow match {
            case Some(dpFlow) =>
                log.debug("Binding dpFlow {} to wcFlow {}", dpFlow, wildFlow)
                flowManager.add(dpFlow, wildFlow)
                metrics.dpFlowsMetric.mark()
            case None =>
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

            if (flow.getMatch.isUserSpaceOnly) {
                // the DP doesn't need to remove userspace-only flows
                self ! flowRemoved(flow)
                return
            }

            datapathConnection.flowsDelete(datapath, flow,
                new NetlinkCallback[Flow] {
                    override def onTimeout() {
                        log.warning("Got a timeout when trying to remove " +
                                    "flow with match {}", flow.getMatch)
                        scheduleRetry()
                    }

                    override def onError(ex: NetlinkException) {
                        log.warning("Got an exception {} when trying to remove " +
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
                        self ! flowRemoved(data)
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

                datapathConnection.flowsGet(datapath, flowMatch,
                new ErrorHandlingCallback[Flow] {

                    def handleError(ex: NetlinkException, timeout: Boolean) {
                        if (ex != null && ex.getErrorCodeEnum == ErrorCode.ENOENT) {
                            self ! flowMissing(flowMatch, flowCallback)
                        } else {
                            log.error("Got an exception {} or timeout {} when " +
                                "trying to flowsGet() for flow match {}",
                                ex, timeout, flowMatch)
                            self ! getFlowOnError(flowCallback)
                        }
                    }

                    def onSuccess(data: Flow) {
                        self ! (if (data != null) {
                            getFlowSucceded(data, flowCallback)
                        } else {
                            log.warning("Unexpected getFlow() result: success, but a null flow")
                            flowMissing(flowMatch, flowCallback)
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
