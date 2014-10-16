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

import java.util.concurrent.TimeUnit
import java.util.concurrent.{ConcurrentHashMap => ConcHashMap}
import java.util.{ArrayList, Set => JSet, Map => JMap}
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
import org.midonet.midolman.flows.WildcardTablesProvider
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.metrics.FlowTablesGauge
import org.midonet.midolman.monitoring.metrics.FlowTablesMeter
import org.midonet.sdn.flows.{FlowTagger, FlowManagerHelper, ManagedWildcardFlow, WildcardFlow, WildcardMatch}
import FlowTagger.FlowTag
import org.midonet.netlink.Callback
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.odp.{Datapath, Flow, FlowMatch}
import org.midonet.sdn.flows.FlowManager
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

    def runCallbacks(callbacks: Iterable[Callback0]) {
        val iter = callbacks.iterator
        while (iter.hasNext) {
            iter.next().call()
        }
    }

}


class FlowController extends Actor with ActorLogWithoutPath {

    import FlowController._
    import DatapathController.DatapathReady

    override def logSource = "org.midonet.flow-management"

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
                    CheckFlowExpiration_)
            }

        case msg@AddWildcardFlow(wildFlow, dpFlow, callbacks, tags, lastInval,
                                 flowMatch, pendingFlowMatches, index) =>
            context.system.eventStream.publish(msg)
            if (FlowController.isTagSetStillValid(lastInval, tags)) {
                wildFlowPool.take.orElse {
                    flowManager.evictOldestFlows()
                    wildFlowPool.take
                } match {
                    case None =>
                        log.warn("Failed to add wildcard flow, no capacity")
                    case Some(managedFlow) =>
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
                    runCallbacks(callbacks)
                    metrics.currentDpFlows = flowManager.getNumDpFlows
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
            if (wildFlow.callbacks != null)
                runCallbacks(wildFlow.callbacks)
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
                                              flowRemovalCallbacks: Seq[Callback0],
                                              tags: ROSet[FlowTag]): Boolean = {

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
            flowManager.add(dpFlow, wildFlow)
            metrics.dpFlowsMetric.mark()
        }
        true
    }

    class FlowManagerInfoImpl() extends FlowManagerHelper {
        val sched = context.system.scheduler

        private def _removeFlow(flowMatch: FlowMatch, retries: Int) {
            def scheduleRetry() {
                if (retries > 0) {
                    log.debug("Scheduling retry of flow deletion with match: {}",
                              flowMatch)
                    sched.scheduleOnce(1 second) {
                        _removeFlow(flowMatch, retries - 1)
                    }
                } else {
                    log.error("Giving up on deleting flow with match: {}",
                              flowMatch)
                }
            }
            datapathConnection(flowMatch).flowsDelete(datapath, flowMatch.getKeys,
                new Callback[Flow] {
                    def onError(ex: NetlinkException) {
                        log.debug("Got an exception {} when trying to remove " +
                                  "flow with match {}", ex, flowMatch)
                        ex.getErrorCodeEnum match {
                            // Success cases, the flow doesn't exist so userspace
                            // can take it as a successful remove:
                            case ErrorCode.ENODEV => notifyRemoval(flowMatch)
                            case ErrorCode.ENOENT => notifyRemoval(flowMatch)
                            case ErrorCode.ENXIO => notifyRemoval(flowMatch)
                            // Retry cases.
                            case ErrorCode.EBUSY => scheduleRetry()
                            case ErrorCode.EAGAIN => scheduleRetry()
                            case ErrorCode.EIO => scheduleRetry()
                            case ErrorCode.EINTR => scheduleRetry()
                            case ErrorCode.ETIMEOUT => scheduleRetry()
                            // Give up
                            case _ =>
                                log.error("Giving up on deleting flow with "+
                                    "match: {} due to: {}", flowMatch, ex)
                        }
                    }

                    def onSuccess(flow: Flow) {
                        notifyRemoval(flow.getMatch)
                    }

                    def notifyRemoval(flowMatch: FlowMatch) {
                        log.debug("DP confirmed removal of flow with match {}", flowMatch)
                    }
                })
        }

        def removeFlow(flowMatch: FlowMatch) {
            metrics.currentDpFlows = flowManager.getNumDpFlows
            _removeFlow(flowMatch, 10)
        }

        def removeWildcardFlow(flow: ManagedWildcardFlow) {
            FlowController.this.removeWildcardFlow(flow)
        }

        def getFlow(flowMatch: FlowMatch, flowCallback: Callback1[Flow] ) {
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
