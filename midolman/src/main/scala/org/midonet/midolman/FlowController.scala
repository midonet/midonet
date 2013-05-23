// Copyright 2012 Midokura Inc.

package org.midonet.midolman

import scala.Some
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

import scala.annotation.tailrec

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.ErrorHandlingCallback
import org.midonet.midolman.flows.WildcardTablesProvider
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.netlink.{Callback => NetlinkCallback}
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.odp.{Datapath, Flow, FlowMatch}
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.util.functors.{Callback0, Callback1}
import org.midonet.sdn.flows.{FlowManagerHelper, WildcardMatch, FlowManager,
                              WildcardFlow}


object FlowController extends Referenceable {
    override val Name = "FlowController"

    case class AddWildcardFlow(wildFlow: WildcardFlow,
                               flow: Option[Flow],
                               flowRemovalCallbacks: ROSet[Callback0],
                               tags: ROSet[Any])

    case class FlowAdded(flow: Flow)

    case class RemoveWildcardFlow(wMatch: WildcardMatch)

    case class InvalidateFlowsByTag(tag: Any)

    case class CheckFlowExpiration()

    case class WildcardFlowAdded(f: WildcardFlow)

    case class WildcardFlowRemoved(f: WildcardFlow)

    case class FlowUpdateCompleted(flow: Flow)

    // TODO(guillermo) tune these values
    private val WILD_FLOW_TABLE_CONCURRENCY_LEVEL = 1
    private val WILD_FLOW_TABLE_LOAD_FACTOR = 0.75f
    private val WILD_FLOW_TABLE_INITIAL_CAPACITY = 65536
    private val WILD_FLOW_PARENT_TABLE_INITIAL_CAPACITY = 256

    private val wildcardTables: JMap[JSet[WildcardMatch.Field],
                                        JMap[WildcardMatch, WildcardFlow]] =
        new ConcHashMap[JSet[WildcardMatch.Field],
                        JMap[WildcardMatch, WildcardFlow]](
            WILD_FLOW_PARENT_TABLE_INITIAL_CAPACITY,
            WILD_FLOW_TABLE_LOAD_FACTOR,
            WILD_FLOW_TABLE_CONCURRENCY_LEVEL)

    private val wildcardTablesProvider: WildcardTablesProvider =
        new WildcardTablesProvider {
            override def tables = wildcardTables

            override def addTable(pattern: JSet[WildcardMatch.Field]) = {
                var table = wildcardTables.get(pattern)
                if (table == null) {
                    table = new ConcHashMap[WildcardMatch, WildcardFlow](
                                    WILD_FLOW_TABLE_INITIAL_CAPACITY,
                                    WILD_FLOW_TABLE_LOAD_FACTOR,
                                    WILD_FLOW_TABLE_CONCURRENCY_LEVEL)

                    wildcardTables.put(pattern, table)
                }
                table
            }
        }

    def queryWildcardFlowTable(flowMatch: FlowMatch): Option[WildcardFlow] = {
        var wildFlow: WildcardFlow = null
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
}

class FlowController extends Actor with ActorLogWithoutPath {

    import FlowController._

    var datapath: Datapath = null

    @Inject
    var midolmanConfig: MidolmanConfig = null

    @Inject
    var datapathConnection: OvsDatapathConnection = null

    var flowManager: FlowManager = null
    var flowManagerHelper: FlowManagerHelper = null

    val tagToFlows: MultiMap[Any, WildcardFlow] =
        new HashMap[Any, mutable.Set[WildcardFlow]]
            with MultiMap[Any, WildcardFlow]

    var flowExpirationCheckInterval: Duration = null

    override def preStart() {
        super.preStart()
        FlowController.wildcardTables.clear()
        val maxDpFlows = midolmanConfig.getDatapathMaxFlowCount
        val maxWildcardFlows = midolmanConfig.getDatapathMaxWildcardFlowCount
        val idleFlowToleranceInterval = midolmanConfig.getIdleFlowToleranceInterval
        flowExpirationCheckInterval = Duration(midolmanConfig.getFlowExpirationInterval,
            TimeUnit.MILLISECONDS)


        flowManagerHelper = new FlowManagerInfoImpl()
        flowManager = new FlowManager(flowManagerHelper,
            FlowController.wildcardTablesProvider ,maxDpFlows, maxWildcardFlows,
            idleFlowToleranceInterval, context.system.eventStream)

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

        case AddWildcardFlow(wildFlow, flow, callbacks, tags) =>
            handleFlowAddedForNewWildcard(wildFlow, flow, callbacks, tags)

        case FlowAdded(dpFlow) =>
            handleFlowAddedForExistingWildcard(dpFlow)

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

        case RemoveWildcardFlow(wmatch) =>
            log.debug("Removing wcflow for match {}", wmatch)
            wildcardTables.get(wmatch.getUsedFields) match {
                case null =>
                case table => table.get(wmatch) match {
                    case null =>
                    case wflow => removeWildcardFlow(wflow)
                }
            }

        case CheckFlowExpiration() =>
            flowManager.checkFlowsExpiration()

        case getFlowSucceded(flow, callback) =>
            log.debug("DP confirmed that flow was updated: {}", flow)
            context.system.eventStream.publish(new FlowUpdateCompleted(flow))
            callback.call(flow)

        case getFlowOnError(callback) => callback.call(null)

        case flowMissing(flowMatch) =>
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

        case flowRemoved(flow) =>
            log.debug("DP confirmed that flow was removed: {}", flow)
            flowManager.removeFlowCompleted(flow)

    }

    case class flowMissing(flowMatch: FlowMatch)
    case class getFlowSucceded(flow: Flow, flowCallback: Callback1[Flow])
    case class getFlowOnError(flowCallback: Callback1[Flow])
    case class flowRemoved(flow: Flow)

    private def removeWildcardFlow(wildFlow: WildcardFlow) {
        @tailrec
        def tagsCleanup(tags: Array[Any], i: Int = 0) {
            if (tags.length > i) {
                tagToFlows.removeBinding(tags(i), wildFlow)
                tagsCleanup(tags, i+1)
            }
        }
        @tailrec
        def runCallbacks(callbacks: Array[Callback0], i: Int = 0) {
            if (callbacks.length > i) {
                callbacks(i).call()
                runCallbacks(callbacks, i+1)
            }
        }

        log.info("removeWildcardFlow - Removing flow {}", wildFlow)
        flowManager.remove(wildFlow)
        tagsCleanup(wildFlow.tags)
        runCallbacks(wildFlow.callbacks)
        context.system.eventStream.publish(new WildcardFlowRemoved(wildFlow))
    }

    private def handleFlowAddedForExistingWildcard(dpFlow: Flow) {
        FlowController.queryWildcardFlowTable(dpFlow.getMatch) match {
            case Some(wildFlow) =>
                // the query doesn't miss, we don't care whether the returned
                // wildcard flow is the same that the client has: the dp flow
                // is valid anyway.
                flowManager.add(dpFlow, wildFlow)
            case None =>
                // The wildFlow timed out or was invalidated in the interval
                // since the client queried for it the first time. We do not
                // re-add the wildcard flow, that would be incorrect if it
                // disappeared due to invalidation. Instead, we remove the dp
                // flow that the client is reporting as added.
                flowManagerHelper.removeFlow(dpFlow)
        }
    }

    private def handleFlowAddedForNewWildcard(wildFlow: WildcardFlow, flow: Option[Flow],
                                              flowRemovalCallbacks: ROSet[Callback0],
                                              tags: ROSet[Any]) {

        if (!flowManager.add(wildFlow)) {
            log.error("FlowManager failed to install wildcard flow {}", wildFlow)
            if (null != flowRemovalCallbacks)
                for (cb <- flowRemovalCallbacks)
                    cb.call()
            return
        }
        context.system.eventStream.publish(new WildcardFlowAdded(wildFlow))
        log.debug("Added wildcard flow {} with tags {}", wildFlow, tags)

        if (null != flowRemovalCallbacks)
            wildFlow.callbacks = flowRemovalCallbacks.toArray
        if (null != tags) {
            wildFlow.tags = tags.toArray
            for (tag <- tags) {
                tagToFlows.addBinding(tag, wildFlow)
            }
        }

        flow match {
            case Some(dpFlow) => flowManager.add(dpFlow, wildFlow)
            case None =>
        }
    }

    class FlowManagerInfoImpl() extends FlowManagerHelper {
        private def _removeFlow(flow: Flow, retries: Int) {
            def scheduleRetry() {
                if (retries > 0) {
                    log.info("Scheduling retry of flow deletion with match: {}",
                             flow.getMatch)
                    context.system.scheduler.scheduleOnce(1 second){
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

        def removeWildcardFlow(flow: WildcardFlow) {
            self ! RemoveWildcardFlow(flow.getMatch)
        }

        def getFlow(flowMatch: FlowMatch, flowCallback: Callback1[Flow] ) {
            log.debug("requesting flow for flow match: {}", flowMatch)

                datapathConnection.flowsGet(datapath, flowMatch,
                new ErrorHandlingCallback[Flow] {

                    def handleError(ex: NetlinkException, timeout: Boolean) {
                        log.error("Got an exception {} or timeout {} when trying" +
                            " to flowsGet()" + "for flow match {}", ex, timeout,
                            flowMatch)
                        self ! getFlowOnError(flowCallback)
                    }

                    def onSuccess(data: Flow) {
                        self ! (if (data != null) getFlowSucceded(data, flowCallback) else flowMissing(flowMatch))
                    }

                })

        }
    }

}
