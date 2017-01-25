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

import java.util.{ArrayDeque, ArrayList}

import org.jctools.queues.SpscArrayQueue

import org.midonet.ErrorCode._
import org.midonet.Util
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.FlowProcessor
import org.midonet.midolman.flows.FlowExpirationIndexer.Expiration
import org.midonet.midolman.flows.{FlowIndexer, FlowTagIndexer, _}
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.management.Metering
import org.midonet.midolman.monitoring.MeterRegistry
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.simulation.PacketContext
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.collection.{ArrayObjectPool, NoOpPool}
import org.midonet.util.concurrent.WakerUpper.Parkable
import org.midonet.util.concurrent.{DisruptorBackChannel, NanoClock}
import org.midonet.util.functors.Callback0

object FlowController {
    private val NoCallbacks = new ArrayList[Callback0]()
    private val NoTags = new ArrayList[FlowTag]()
    private[midolman] val IndexShift = 28 // Leave 4 bits for the work ID
    private[midolman] val IndexMask = (1 << IndexShift) - 1
}


class FlowTablePreallocation(config: MidolmanConfig) extends MidolmanLogging {
    import FlowController._

    override def logSource: String = "org.midonet.midolman.prealloc"

    private val indexToFlows = new ArrayList[Array[ManagedFlow]](
        config.simulationThreads)
    private val managedFlowPools = new ArrayList[ArrayObjectPool[ManagedFlow]](
        config.simulationThreads)
    private val meterRegistries = new ArrayList[MeterRegistry](
        config.simulationThreads)

    private val errorExpirationQueues = new ArrayList[ArrayDeque[ManagedFlow]](
        config.simulationThreads)
    private val flowExpirationQueues = new ArrayList[ArrayDeque[ManagedFlow]](
        config.simulationThreads)
    private val statefulFlowExpirationQueues =
        new ArrayList[ArrayDeque[ManagedFlow]](config.simulationThreads)
    private val tunnelFlowExpirationQueues =
        new ArrayList[ArrayDeque[ManagedFlow]](config.simulationThreads)

    val maxFlows = Math.min(
        ((config.datapath.maxFlowCount /
              config.simulationThreads) * 1.2).toInt,
        (1 << IndexShift) - 1)

    def allocateAndTenure() {
        var i = 0
        val indexToFlowsSize = Util.findNextPositivePowerOfTwo(maxFlows)
        log.info("Preallocating flow table structures, max flows = "
                     + s"${config.datapath.maxFlowCount}, worker threads = "
                     + s"${config.simulationThreads}")
        while (i < config.simulationThreads) {
            indexToFlows.add(new Array[ManagedFlow](indexToFlowsSize))
            managedFlowPools.add(new ArrayObjectPool[ManagedFlow](
                                     maxFlows, new ManagedFlow(_)))
            meterRegistries.add(new MeterRegistry(maxFlows))

            errorExpirationQueues.add(new ArrayDeque(maxFlows/3))
            flowExpirationQueues.add(new ArrayDeque(maxFlows))
            statefulFlowExpirationQueues.add(new ArrayDeque(maxFlows))
            tunnelFlowExpirationQueues.add(new ArrayDeque(maxFlows/3))

            i += 1
        }
        log.info("Running manual GC to tenure pre-allocated objects")
        System.gc()
    }

    def takeIndexToFlow(): Array[ManagedFlow] = indexToFlows.remove(0)
    def takeManagedFlowPool(): ArrayObjectPool[ManagedFlow] =
        managedFlowPools.remove(0)
    def takeMeterRegistry(): MeterRegistry = meterRegistries.remove(0)
    def takeErrorExpirationQueue(): ArrayDeque[ManagedFlow] =
        errorExpirationQueues.remove(0)
    def takeFlowExpirationQueue(): ArrayDeque[ManagedFlow] =
        flowExpirationQueues.remove(0)
    def takeStatefulFlowExpirationQueue(): ArrayDeque[ManagedFlow] =
        statefulFlowExpirationQueues.remove(0)
    def takeTunnelFlowExpirationQueue(): ArrayDeque[ManagedFlow] =
        tunnelFlowExpirationQueues.remove(0)
}

trait FlowController extends FlowIndexer with FlowTagIndexer
                     with FlowExpirationIndexer with DisruptorBackChannel {
    import FlowController._

    protected val config: MidolmanConfig
    protected val clock: NanoClock
    protected val flowProcessor: FlowProcessor
    protected val datapathId: Int
    protected val workerId: Int
    val metrics: PacketPipelineMetrics

    protected val preallocation: FlowTablePreallocation

    private var curIndex = -1
    private var numFlows = 0

    private var indexToFlow = preallocation.takeIndexToFlow()
    private var mask = indexToFlow.length - 1

    val meters = preallocation.takeMeterRegistry()
    Metering.registerAsMXBean(meters)

    private val managedFlowPool = preallocation.takeManagedFlowPool()

    private val oversubscriptionFlowPool = new NoOpPool[ManagedFlow](
        new ManagedFlow(_))

    private val completedFlowOperations = new SpscArrayQueue[FlowOperation](
        flowProcessor.capacity)
    private val pooledFlowOperations = new ArrayObjectPool[FlowOperation](
        flowProcessor.capacity, new FlowOperation(_, completedFlowOperations))
    private val flowRemoveCommandsToRetry = new ArrayList[FlowOperation](
        flowProcessor.capacity)

    def addFlow(context: PacketContext, expiration: Expiration): Unit = {
        val flow = takeFlow()
        if (context.isRecirc) {
            // Since linked flows are deleted later, and we have to delete
            // the inner packets flow first, make that the main ManagedFlow
            val outerFlow = takeFlow()
            outerFlow.reset(
                context.origMatch, NoTags, NoCallbacks,
                0L, expiration, clock.tick, flow)
            flow.reset(
                context.recircMatch, context.flowTags, context.flowRemovedCallbacks,
                0L, expiration, clock.tick, outerFlow)
        } else {
            flow.reset(
                context.origMatch, context.flowTags, context.flowRemovedCallbacks,
                0L, expiration, clock.tick)
        }
        registerFlow(flow)
        context.flow = flow
        context.log.debug(s"Added flow $flow")
    }

    private def takeFlow() = {
        val flow = managedFlowPool.take
        if (flow ne null)
            flow
        else
            oversubscriptionFlowPool.take
    }

    def duplicateFlow(mark: Int): Unit = {
        val flow = indexToFlow(mark & mask)
        if ((flow ne null) && flow.mark == mark && !flow.removed) {
            log.debug(s"Removing duplicate flow $flow")
            forgetFlow(flow)
            var flowsRemoved = 1
            if (flow.linkedFlow ne null) {
                removeFlowFromDatapath(flow.linkedFlow)
                forgetFlow(flow.linkedFlow)
                flowsRemoved += 1
            }
            metrics.dpFlowsRemovedMetric.mark(flowsRemoved)
        }
    }

    override def shouldProcess = completedFlowOperations.size > 0

    override def process(): Unit = {
        processCompletedFlowOperations()
        checkFlowsExpiration(clock.tick)
    }

    override def registerFlow(flow: ManagedFlow): Unit = {
        super.registerFlow(flow)
        indexFlow(flow)
        meters.trackFlow(flow.flowMatch, flow.tags)
        var flowsAdded = 1
        if (flow.linkedFlow ne null) {
            indexFlow(flow.linkedFlow)
            flow.linkedFlow.ref()
            flowsAdded += 1
        }
        metrics.dpFlowsMetric.mark(flowsAdded)
        flow.ref()
    }

    override def removeFlow(flow: ManagedFlow): Unit =
        if (!flow.removed) {
            removeFlowFromDatapath(flow)
            forgetFlow(flow)
            var flowsRemoved = 1
            if (flow.linkedFlow ne null) {
                removeFlowFromDatapath(flow.linkedFlow)
                forgetFlow(flow.linkedFlow)
                flowsRemoved += 1
            }
            metrics.dpFlowsRemovedMetric.mark(flowsRemoved)
        }

    private def forgetFlow(flow: ManagedFlow): Unit = {
        super.removeFlow(flow)
        clearFlowIndex(flow)
        flow.callbacks.runAndClear()
        flow.removed = true
        flow.unref()
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
            val seq = cmd.managedFlow.sequence
            flowProcessor.tryEject(seq, datapathId, fmatch, cmd)
            i += 1
        }
        flowRemoveCommandsToRetry.clear()
    }

    private def flowDeleteFailed(req: FlowOperation): Unit = {
        log.debug("Got an exception when trying to remove " +
                  s"${req.managedFlow}", req.failure)
        req.netlinkErrorCode match {
            case EBUSY | EAGAIN | EIO | EINTR | ETIMEOUT if req.retries > 0 =>
                scheduleRetry(req)
                return
            case ENODEV | ENOENT | ENXIO =>
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
        val flowMetadata = req.flowMetadata
        val flowMatch = req.managedFlow.flowMatch
        log.debug(s"DP confirmed removal of ${req.managedFlow}")
        meters.updateFlow(flowMatch, flowMetadata.getStats)
        meters.forgetFlow(flowMatch)
        req.clear()
    }

    private val flowOperationParkable = new Parkable {
        override def shouldWakeUp() = completedFlowOperations.size > 0
    }

    private def takeFlowOperation(flow: ManagedFlow): FlowOperation = {
        var flowOp: FlowOperation = null
        while ({ flowOp = pooledFlowOperations.take; flowOp } eq null) {
            processCompletedFlowOperations()
            if (pooledFlowOperations.available == 0) {
                log.debug("Parking until the pending flow operations complete")
                flowOperationParkable.park()
            }
        }
        flowOp.reset(flow, retries = 10)
        flowOp
    }

    private def removeFlowFromDatapath(flow: ManagedFlow): Unit = {
        log.debug(s"Removing flow $flow from datapath")
        val flowOp = takeFlowOperation(flow)
        // Spin while we try to eject the flow. This can happen if we invalidated
        // a flow so close to its creation that it has not been created yet.
        while (!flowProcessor.tryEject(flow.sequence, datapathId,
                                       flow.flowMatch, flowOp)) {
            processCompletedFlowOperations()
            Thread.`yield`()
        }
    }

    private def indexFlow(flow: ManagedFlow): Unit = {
        numFlows += 1
        ensureCapacity()
        var index = 0
        do {
            curIndex += 1
            index = curIndex & mask
        } while (indexToFlow(index) ne null)
        indexToFlow(index) = flow
        flow.mark = (curIndex & IndexMask) | (workerId << IndexShift)
    }

    private def clearFlowIndex(flow: ManagedFlow): Unit = {
        indexToFlow(flow.mark & mask) = null
        numFlows -= 1
    }

    private def ensureCapacity(): Unit = {
        if (numFlows > indexToFlow.length) {
            val newIndexToFlow = new Array[ManagedFlow](indexToFlow.length * 2)
            Array.copy(indexToFlow, 0, newIndexToFlow, 0, indexToFlow.length)
            indexToFlow = newIndexToFlow
            mask = indexToFlow.length - 1
        }
    }
}
