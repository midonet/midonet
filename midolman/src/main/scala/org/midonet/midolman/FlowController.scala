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

import java.util.concurrent.TimeUnit
import java.util.ArrayList

import scala.collection.mutable
import scala.collection.mutable.{HashMap, MultiMap}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import akka.actor.{Actor, ActorSystem}
import akka.event.LoggingReceive

import com.google.inject.Inject

import rx.Observer

import org.jctools.queues.SpscArrayQueue

import com.codahale.metrics.MetricRegistry.name
import com.codahale.metrics.{Gauge, MetricRegistry}

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.FlowProcessor
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.metrics.{FlowTablesGauge, FlowTablesMeter}
import org.midonet.midolman.management.Metering
import org.midonet.midolman.monitoring.MeterRegistry
import org.midonet.midolman.simulation.PacketContext
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.odp.{FlowMetadata, Datapath, Flow, FlowMatch}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.flows._
import org.midonet.util.collection.{EventHistory, ArrayObjectPool, ObjectPool}
import org.midonet.util.collection.EventHistory._
import org.midonet.util.concurrent.WakerUpper.Parkable
import org.midonet.util.functors.Callback1

object FlowController extends Referenceable {
    override val Name = "FlowController"

    case class WildcardFlowAdded(f: ManagedFlow)

    case class WildcardFlowRemoved(f: ManagedFlow)

    case class InvalidateFlowsByTag(tag: FlowTag)

    case class FlowUpdateCompleted(flow: Flow) // used in test only

    case object CheckFlowExpiration_

    case class FlowMissing_(flowMatch: FlowMatch, flowCallback: Callback1[Flow])

    /** NOTE(guillermo): we include an 'origMatch' here because 'userspace' keys
      * are lost in the trip to the kernel, and that plays badly with out book
      * keeping, in particular with that of the MetricsRegistry. */
    case class GetFlowSucceeded_(flow: Flow, origMatch: FlowMatch, flowCallback: Callback1[Flow])
    case class GetFlowFailed_(flowCallback: Callback1[Flow])

    case object CheckCompletedRequests

    val MIN_WILDCARD_FLOW_CAPACITY = 4096

    private val invalidationHistory = new EventHistory[FlowTag](1024)


    def isTagSetStillValid(pktCtx: PacketContext) =
        invalidationHistory.existsSince(pktCtx.lastInvalidation,
                                        pktCtx.flowTags) match {
            case EventSearchWindowMissed => pktCtx.flowTags.isEmpty
            case EventSeen => false
            case EventNotSeen => true
        }

    def lastInvalidationEvent = invalidationHistory.latest

    object FlowOperation {
        val GET: Byte = 0
        val DELETE: Byte = 1
    }

    final class FlowOperation(pool: ObjectPool[FlowOperation],
                              completedRequests: SpscArrayQueue[FlowOperation])
                             (implicit actorSystem: ActorSystem)
        extends Observer[ByteBuffer] {

        val flowMetadata = new FlowMetadata()

        var opId: Byte = _
        var managedFlow: ManagedFlow = _
        var retries: Byte = _
        var failure: Throwable = _

        def isFailed = failure ne null

        def reset(opId: Byte, managedFlow: ManagedFlow, retries: Byte): Unit = {
            this.opId = opId
            this.managedFlow = managedFlow
            this.retries = retries
            managedFlow.ref()
        }

        def netlinkErrorCode =
            failure match {
                case ex: NetlinkException => ex.getErrorCodeEnum
                case _ => NetlinkException.GENERIC_IO_ERROR
            }

        def clear(): Unit = {
            failure = null
            managedFlow.unref()
            managedFlow = null
            flowMetadata.clear()
            pool.offer(this)
        }

        override def onNext(t: ByteBuffer): Unit =
            try {
                flowMetadata.deserialize(t)
            } catch { case e: Throwable =>
                failure = e
            }

        override def onCompleted(): Unit = {
            completedRequests.offer(this)
            FlowController ! FlowController.CheckCompletedRequests
        }

        override def onError(e: Throwable): Unit = {
            failure = e
            onCompleted()
        }
    }
}

class FlowController extends Actor with ActorLogWithoutPath
        with DatapathReadySubscriberActor {
    import DatapathController.DatapathReady
    import FlowController._

    override def logSource = "org.midonet.flow-management"

    implicit val system = this.context.system

    var datapath: Datapath = null
    var datapathId: Int = _

    @Inject
    var midolmanConfig: MidolmanConfig = null

    @Inject
    var datapathConnPool: DatapathConnectionPool = null

    def datapathConnection(flowMatch: FlowMatch) = datapathConnPool.get(flowMatch.hashCode)

    @Inject
    var flowProcessor: FlowProcessor = null

    var pooledFlowOperations: ArrayObjectPool[FlowOperation] = _
    var completedFlowOperations: SpscArrayQueue[FlowOperation] = _
    val flowRemoveCommandsToRetry = new ArrayList[FlowOperation]()

    @Inject
    var metricsRegistry: MetricRegistry = null

    var meters: MeterRegistry = null

    var flowManager: FlowManager = null
    var flowManagerHelper: FlowManagerHelper = null

    val tagToFlows: MultiMap[FlowTag, ManagedFlow] =
        new HashMap[FlowTag, mutable.Set[ManagedFlow]]
            with MultiMap[FlowTag, ManagedFlow]

    var flowExpirationCheckInterval: FiniteDuration = null

    private var managedFlowPool: ObjectPool[ManagedFlow] = null

    var metrics: FlowTablesMetrics = null

    private[this] implicit def executor: ExecutionContext = context.dispatcher

    override def preStart() {
        super.preStart()
        meters = new MeterRegistry(midolmanConfig.getDatapathMaxFlowCount)
        Metering.registerAsMXBean(meters)
        val maxDpFlows = (midolmanConfig.getDatapathMaxFlowCount * 1.1).toInt
        val idleFlowToleranceInterval = midolmanConfig.getIdleFlowToleranceInterval
        flowExpirationCheckInterval = Duration(midolmanConfig.getFlowExpirationInterval,
            TimeUnit.MILLISECONDS)

        flowManagerHelper = new FlowManagerInfoImpl()
        flowManager = new FlowManager(flowManagerHelper, maxDpFlows, idleFlowToleranceInterval)

        managedFlowPool = new ArrayObjectPool(maxDpFlows, new ManagedFlow(_))

        metrics = new FlowTablesMetrics(flowManager)

        pooledFlowOperations = new ArrayObjectPool(
            flowProcessor.capacity,
            new FlowOperation(_, completedFlowOperations))
        completedFlowOperations = new SpscArrayQueue(flowProcessor.capacity)
    }

    def receive = LoggingReceive {
        case DatapathReady(dp, dpState) => if (null == datapath) {
            datapath = dp
            datapathId = dp.getIndex
            // schedule next check for flow expiration after 20 ms and then after
            // every flowExpirationCheckInterval ms
            context.system.scheduler.schedule(20 millis,
                flowExpirationCheckInterval,
                self,
                CheckFlowExpiration_)
        }

        case pktCtx: PacketContext  =>
            var managedFlow = managedFlowPool.take
            if (managedFlow eq null)
                managedFlow = new ManagedFlow(managedFlowPool)

            managedFlow.reset(pktCtx)
            managedFlow.ref()

            if (FlowController.isTagSetStillValid(pktCtx)) {
                if (handleFlowAddedForNewWildcard(managedFlow, pktCtx)) {
                    context.system.eventStream.publish(WildcardFlowAdded(managedFlow))
                    log.debug(s"Added flow $managedFlow")
                } else {
                    managedFlow.unref()
                }
            } else {
                log.debug(s"Skipping obsolete flow with match ${pktCtx.origMatch} " +
                          s"and tags ${pktCtx.flowTags}")
                flowManagerHelper removeFlow managedFlow
                pktCtx.callbackExecutor.schedule(pktCtx.flowRemovedCallbacks)
                managedFlow.unref()   // the FlowController's ref
            }

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

        case CheckFlowExpiration_ =>
            flowManager.checkFlowsExpiration()

        case GetFlowSucceeded_(flow, origMatch, callback) =>
            log.debug("Retrieved flow from datapath: {}", flow.getMatch)
            context.system.eventStream.publish(FlowUpdateCompleted(flow))
            callback.call(flow)
            if (flow.getStats ne null)
                meters.updateFlow(origMatch, flow.getStats)

        case GetFlowFailed_(callback) =>
            callback.call(null)

        case FlowMissing_(flowMatch, callback) =>
            callback.call(null)
            meters.forgetFlow(flowMatch)

        case CheckCompletedRequests =>
            processCompletedFlowOperations()
    }

    private def removeWildcardFlow(wildFlow: ManagedFlow) {
        def tagsCleanup(tags: ArrayList[FlowTag]): Unit = {
            var i = 0
            while (i < tags.size()) {
                tagToFlows.removeBinding(tags.get(i), wildFlow)
                i += 1
            }
        }

        if (flowManager.remove(wildFlow)) {
            tagsCleanup(wildFlow.tags)
            wildFlow.cbExecutor.schedule(wildFlow.callbacks)
            context.system.eventStream.publish(WildcardFlowRemoved(wildFlow))
            wildFlow.unref() // FlowController's ref
        }
    }

    private def handleFlowAddedForNewWildcard(wildFlow: ManagedFlow,
                                              pktCtx: PacketContext): Boolean = {

        if (!flowManager.add(wildFlow)) {
            log.debug("FlowManager failed to install wildcard flow {}", wildFlow)
            pktCtx.callbackExecutor.schedule(pktCtx.flowRemovedCallbacks)
            return false
        }

        val it = pktCtx.flowTags.iterator()
        while (it.hasNext) {
            tagToFlows.addBinding(it.next(), wildFlow)
        }

        meters.trackFlow(pktCtx.origMatch, wildFlow.tags)

        metrics.dpFlowsMetric.mark()
        true
    }

    private def processCompletedFlowOperations(): Unit = {
        var req: FlowOperation = null
        while ({ req = completedFlowOperations.poll(); req } ne null) {
            if (req.isFailed) {
                if (req.opId == FlowOperation.DELETE)
                    flowDeleteFailed(req)
                else
                    flowRetrievalFailed(req)
            } else {
                if (req.opId == FlowOperation.DELETE)
                    flowDeleteSucceeded(req)
                else
                    flowRetrievalSucceeded(req)
            }
        }
        retryFailedFlowOperations()
    }

    private def retryFailedFlowOperations(): Unit = {
        var i = 0
        while (i < flowRemoveCommandsToRetry.size()) {
            val cmd = flowRemoveCommandsToRetry.get(i)
            val fmatch = cmd.managedFlow.flowMatch
            flowProcessor.tryEject(fmatch.getSequence, datapathId, fmatch, cmd)
            i += 1
        }
        flowRemoveCommandsToRetry.clear()
    }

    private def flowRetrievalFailed(req: FlowOperation): Unit = {
        req.netlinkErrorCode match {
            case ErrorCode.ENOENT =>
                flowManager.retrievedFlow(null, req.managedFlow)
                meters.forgetFlow(req.managedFlow.flowMatch)
            case other =>
                log.error("Got exception when trying to retrieve " +
                          s"${req.managedFlow}", req.failure)
        }
        req.clear()
    }

    private def flowRetrievalSucceeded(req: FlowOperation): Unit = {
        log.debug(s"Retrieved flow from datapath: ${req.managedFlow}")
        context.system.eventStream.publish(FlowUpdateCompleted)
        flowManager.retrievedFlow(req.flowMetadata, req.managedFlow)
        req.clear()
    }

    private def flowDeleteFailed(req: FlowOperation): Unit = {
        log.debug("Got an exception when trying to remove " +
                  s"${req.managedFlow}", req.failure)
        req.netlinkErrorCode match {
            case ErrorCode.EBUSY | ErrorCode.EAGAIN | ErrorCode.EIO |
                 ErrorCode.EINTR | ErrorCode.ETIMEOUT if req.retries > 0 =>
                scheduleRetry(req)
                return
            case ErrorCode.ENODEV | ErrorCode.ENOENT | ErrorCode.ENXIO =>
                log.debug(s"${req.managedFlow} was already deleted")
            case _ =>
                log.error(s"Failed to delete ${req.managedFlow}", req.failure)
        }
        meters.forgetFlow(req.managedFlow.flowMatch)
        req.clear()
    }

    private def scheduleRetry(req: FlowOperation): Unit = {
        req.retries = (req.retries - 1).toByte
        req.failure = null
        log.debug(s"Scheduling retry of flow ${req.managedFlow}")
        flowRemoveCommandsToRetry.add(req)
    }

    private def flowDeleteSucceeded(req: FlowOperation): Unit = {
        // Note: we use the request's FlowMatch because any userspace keys
        // that we added to it are no present in the kernel's response and we
        // need them for our bookkeeping, in particular for the MetricsRegistry.
        val flowMetadata = req.flowMetadata
        val flowMatch = req.managedFlow.flowMatch
        log.debug(s"DP confirmed removal of ${req.managedFlow}")
        meters.updateFlow(flowMatch, flowMetadata.getStats)
        meters.forgetFlow(flowMatch)
        req.clear()
    }

    sealed class FlowManagerInfoImpl extends FlowManagerHelper with Parkable {

        override def shouldWakeUp() = completedFlowOperations.size > 0

        private def takeFlowOperation(flow: ManagedFlow,
                                      op: Byte, retries: Byte): FlowOperation = {
            var flowOp: FlowOperation = null
            while ({ flowOp = pooledFlowOperations.take; flowOp } eq null) {
                processCompletedFlowOperations()
                park()
            }
            flowOp.reset(op, flow, 10)
            flowOp
        }

        def removeFlow(flow: ManagedFlow): Unit = {
            val flowOp = takeFlowOperation(flow, FlowOperation.DELETE, 10)
            val fmatch = flow.flowMatch
            // Spin while we try to eject the flow. At this point, the only
            // reason it can fail is if the simulation tags are not valid and
            // we can't delete the flow because the corresponding flow create
            // hasn't been written out. Note that this is going away when we
            // partition the FlowController.
            while (!flowProcessor.tryEject(fmatch.getSequence, datapathId,
                                           fmatch, flowOp)) {
                processCompletedFlowOperations()
                Thread.`yield`()
            }
        }

        def getFlow(flow: ManagedFlow): Unit = {
            val flowOp = takeFlowOperation(flow, FlowOperation.GET, 0)
            flowProcessor.tryGet(datapathId, flow.flowMatch, flowOp)
        }

        def removeWildcardFlow(flow: ManagedFlow): Unit = {
            FlowController.this.removeWildcardFlow(flow)
        }
    }

    class FlowTablesMetrics(val flowManager: FlowManager) {
        val currentDpFlowsMetric = metricsRegistry.register(name(
                classOf[FlowTablesGauge], "currentDatapathFlows"),
            new Gauge[Long]{
                 override def getValue = flowManager.getNumDpFlows
             })

        val dpFlowsMetric = metricsRegistry.meter(name(
                classOf[FlowTablesMeter], "datapathFlowsCreated",
                "datapathFlows"))
    }
}
