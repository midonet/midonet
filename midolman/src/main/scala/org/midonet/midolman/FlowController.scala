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

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import akka.actor.{Actor, ActorSystem}
import akka.event.LoggingReceive
import com.codahale.metrics.MetricRegistry.name
import com.codahale.metrics.{Gauge, MetricRegistry}
import com.google.inject.Inject
import org.jctools.queues.SpscArrayQueue
import rx.Observer

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.FlowProcessor
import org.midonet.midolman.flows.{FlowLifecycle, FlowInvalidation, FlowInvalidator}
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.metrics.{FlowTablesGauge, FlowTablesMeter}
import org.midonet.midolman.management.Metering
import org.midonet.midolman.monitoring.MeterRegistry
import org.midonet.midolman.simulation.PacketContext
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.odp.{Datapath, FlowMetadata}
import org.midonet.sdn.flows._
import org.midonet.util.collection.{ArrayObjectPool, ObjectPool}
import org.midonet.util.concurrent.WakerUpper.Parkable

object FlowController extends Referenceable {
    override val Name = "FlowController"

    case class WildcardFlowAdded(f: ManagedFlow)

    case class WildcardFlowRemoved(f: ManagedFlow)

    case object FlowUpdateCompleted

    case object CheckFlowExpiration_

    case object CheckCompletedRequests

    val MIN_WILDCARD_FLOW_CAPACITY = 4096

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
            reset(managedFlow, retries)
        }

        def reset(managedFlow: ManagedFlow, retries: Byte): Unit = {
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
        with DatapathReadySubscriberActor with FlowLifecycle with FlowInvalidation {
    import DatapathController.DatapathReady
    import FlowController._

    override def logSource = "org.midonet.flow-management"

    implicit val system = this.context.system

    var datapath: Datapath = null
    var datapathId: Int = _

    @Inject
    var midolmanConfig: MidolmanConfig = null

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

    @Inject
    var flowInvalidator: FlowInvalidator = _

    var flowExpirationCheckInterval: FiniteDuration = null

    private var managedFlowPool: ObjectPool[ManagedFlow] = null

    var metrics: FlowTablesMetrics = null

    private[this] implicit def executor: ExecutionContext = context.dispatcher

    override def preStart() {
        super.preStart()
        meters = new MeterRegistry(midolmanConfig.datapath.maxFlowCount)
        Metering.registerAsMXBean(meters)
        val maxDpFlows = (midolmanConfig.datapath.maxFlowCount * 1.1).toInt
        flowExpirationCheckInterval = Duration(10000, // FIXME - being removed
            TimeUnit.MILLISECONDS)

        flowManagerHelper = new FlowManagerInfoImpl()
        flowManager = new FlowManager(flowManagerHelper, maxDpFlows)

        managedFlowPool = new ArrayObjectPool(maxDpFlows, new ManagedFlow(_))

        metrics = new FlowTablesMetrics(flowManager)

        completedFlowOperations = new SpscArrayQueue(flowProcessor.capacity)
        pooledFlowOperations = new ArrayObjectPool(
            flowProcessor.capacity,
            new FlowOperation(_, completedFlowOperations))
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

            if (FlowInvalidation.isTagSetStillValid(pktCtx)) {
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

        case CheckFlowExpiration_ =>
            flowManager.checkFlowsExpiration()

        case CheckCompletedRequests =>
            processCompletedFlowOperations()
            flowInvalidator.process(this)
    }

    override def removeFlow(flow: ManagedFlow) {
        if (flowManager.remove(flow)) {
            super.removeFlow(flow)
            flow.cbExecutor.schedule(flow.callbacks)
            context.system.eventStream.publish(WildcardFlowRemoved(flow))
            flow.unref() // FlowController's ref
        }
    }

    private def handleFlowAddedForNewWildcard(flow: ManagedFlow,
                                              pktCtx: PacketContext): Boolean = {

        if (!flowManager.add(flow)) {
            log.debug("FlowManager failed to install wildcard flow {}", flow)
            pktCtx.callbackExecutor.schedule(pktCtx.flowRemovedCallbacks)
            return false
        }

        registerFlow(flow)

        meters.trackFlow(pktCtx.origMatch, flow.tags)

        metrics.dpFlowsMetric.mark()
        true
    }

    private def processCompletedFlowOperations(): Unit = {
        var req: FlowOperation = null
        while ({ req = completedFlowOperations.poll(); req } ne null) {
            if (req.isFailed) {
                flowDeleteFailed(req)
            } else {
                flowDeleteSucceeded(req)
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

        private def takeFlowOperation(flow: ManagedFlow): FlowOperation = {
            var flowOp: FlowOperation = null
            while ({ flowOp = pooledFlowOperations.take; flowOp } eq null) {
                processCompletedFlowOperations()
                if (pooledFlowOperations.available == 0)
                    park()
            }
            flowOp.reset(flow, retries = 10)
            flowOp
        }

        def removeFlow(flow: ManagedFlow): Unit = {
            val flowOp = takeFlowOperation(flow)
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

        def removeWildcardFlow(flow: ManagedFlow): Unit = {
            FlowController.this.removeFlow(flow)
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
