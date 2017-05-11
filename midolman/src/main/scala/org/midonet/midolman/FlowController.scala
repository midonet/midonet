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
import org.midonet.odp.FlowMatch
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.FlowProcessor
import org.midonet.midolman.flows.FlowExpirationIndexer.Expiration
import org.midonet.midolman.flows.FlowExpirationIndexer.ExpirationQueue
import org.midonet.midolman.flows._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.monitoring.MeterRegistry
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.simulation.PacketContext
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.collection.{ArrayObjectPool, NoOpPool}
import org.midonet.util.concurrent.WakerUpper.Parkable
import org.midonet.util.concurrent.{DisruptorBackChannel, NanoClock}
import org.midonet.util.functors.Callback0

object FlowController {
    val NoCallbacks = new ArrayList[Callback0]()
    val NoTags = new ArrayList[FlowTag]()
    private[midolman] val IndexShift = 28 // Leave 4 bits for the work ID
    private[midolman] val IndexMask = (1 << IndexShift) - 1
}


class FlowTablePreallocation(config: MidolmanConfig) extends MidolmanLogging {
    import FlowController._

    override def logSource: String = "org.midonet.midolman.prealloc"

    private val numWorkers = PacketWorkersService.numWorkers(config)

    private val indexToFlows = new ArrayList[Array[ManagedFlowImpl]](numWorkers)
    private val managedFlowPools = new ArrayList[ArrayObjectPool[ManagedFlowImpl]](
        numWorkers)
    private val meterRegistries = new ArrayList[MeterRegistry](numWorkers)

    private val errorExpirationQueues = new ArrayList[ExpirationQueue](
        numWorkers)
    private val flowExpirationQueues = new ArrayList[ExpirationQueue](
        numWorkers)
    private val statefulFlowExpirationQueues =
        new ArrayList[ExpirationQueue](numWorkers)
    private val tunnelFlowExpirationQueues =
        new ArrayList[ExpirationQueue](numWorkers)


    val maxFlows = Math.min(
        ((config.datapath.maxFlowCount / numWorkers) * 1.2).toInt,
        (1 << IndexShift) - 1)

    def allocateAndTenure() {
        var i = 0
        val indexToFlowsSize = Util.findNextPositivePowerOfTwo(maxFlows)
        log.info("Preallocating flow table structures, max flows = "
                     + s"${config.datapath.maxFlowCount}, worker threads = "
                     + s"${numWorkers}")
        while (i < numWorkers) {
            indexToFlows.add(new Array[ManagedFlowImpl](indexToFlowsSize))
            managedFlowPools.add(new ArrayObjectPool[ManagedFlowImpl](
                                     maxFlows, new ManagedFlowImpl(_)))
            meterRegistries.add(new MeterRegistry(maxFlows))

            errorExpirationQueues.add(new ExpirationQueue(maxFlows/3))
            flowExpirationQueues.add(new ExpirationQueue(maxFlows))
            statefulFlowExpirationQueues.add(new ExpirationQueue(maxFlows))
            tunnelFlowExpirationQueues.add(new ExpirationQueue(maxFlows/3))

            i += 1
        }
        log.info("Running manual GC to tenure pre-allocated objects")
        System.gc()
    }

    def takeIndexToFlow(): Array[ManagedFlowImpl] = indexToFlows.remove(0)
    def takeManagedFlowPool(): ArrayObjectPool[ManagedFlowImpl] =
        managedFlowPools.remove(0)
    def takeMeterRegistry(): MeterRegistry = meterRegistries.remove(0)
    def takeErrorExpirationQueue(): ExpirationQueue =
        errorExpirationQueues.remove(0)
    def takeFlowExpirationQueue(): ExpirationQueue =
        flowExpirationQueues.remove(0)
    def takeStatefulFlowExpirationQueue(): ExpirationQueue =
        statefulFlowExpirationQueues.remove(0)
    def takeTunnelFlowExpirationQueue(): ExpirationQueue =
        tunnelFlowExpirationQueues.remove(0)
}


trait FlowController extends DisruptorBackChannel {
    def addFlow(fmatch: FlowMatch, flowTags: ArrayList[FlowTag],
                removeCallbacks: ArrayList[Callback0],
                expiration: Expiration): ManagedFlow
    def addRecircFlow(fmatch: FlowMatch,
                      recircMatch: FlowMatch,
                      flowTags: ArrayList[FlowTag],
                      removeCallbacks: ArrayList[Callback0],
                      expiration: Expiration): ManagedFlow
    def removeDuplicateFlow(mark: Int): Unit
    def flowExists(mark: Int): Boolean

    def invalidateFlowsFor(tag: FlowTag): Unit
}

trait FlowControllerDeleter {
    def removeFlowFromDatapath(flowMatch: FlowMatch, sequence: Long): Unit
    def processCompletedFlowOperations(): Unit
    def shouldProcess: Boolean
}

class FlowControllerImpl(config: MidolmanConfig,
                         clock: NanoClock,
                         flowProcessor: FlowProcessor,
                         datapathId: Int,
                         workerId: Int,
                         metrics: PacketPipelineMetrics,
                         meters: MeterRegistry,
                         preallocation: FlowTablePreallocation)
        extends FlowController with DisruptorBackChannel with MidolmanLogging {
    import FlowController._

    private var curIndex = -1
    private var numFlows = 0

    private var indexToFlow = preallocation.takeIndexToFlow()
    private var mask = indexToFlow.length - 1

    private val managedFlowPool = preallocation.takeManagedFlowPool()
    private val tagIndexer = new FlowTagIndexer
    private val expirationIndexer = new FlowExpirationIndexer(preallocation)
    private val deleter = new FlowControllerDeleterImpl(flowProcessor,
                                                        datapathId,
                                                        meters)

    private val oversubscriptionFlowPool = new NoOpPool[ManagedFlowImpl](
        new ManagedFlowImpl(_))

    override def addFlow(fmatch: FlowMatch, flowTags: ArrayList[FlowTag],
                         removeCallbacks: ArrayList[Callback0],
                         expiration: Expiration): ManagedFlow = {
        val flow = takeFlow()
        flow.reset(fmatch, flowTags, removeCallbacks,
                   0L, expiration, clock.tick)
        registerFlow(flow)
        flow
    }

    override def addRecircFlow(fmatch: FlowMatch,
                               recircMatch: FlowMatch,
                               flowTags: ArrayList[FlowTag],
                               removeCallbacks: ArrayList[Callback0],
                               expiration: Expiration): ManagedFlow = {
        val flow = takeFlow()
        // Since linked flows are deleted later, and we have to delete
        // the inner packets flow first, make that the main ManagedFlow
        val outerFlow = takeFlow()
        outerFlow.reset(fmatch, NoTags, NoCallbacks,
                        0L, expiration, clock.tick, flow)
        flow.reset(recircMatch, flowTags, removeCallbacks,
                   0L, expiration, clock.tick, outerFlow)
        registerFlow(flow)
        flow
    }

    private def takeFlow() = {
        val flow = managedFlowPool.take
        if (flow ne null) {
            flow.ref()
            flow
        } else {
            oversubscriptionFlowPool.take
        }
    }

    override def removeDuplicateFlow(mark: Int): Unit = {
        val flow = indexToFlow(mark & mask)
        if ((flow ne null) && flow.mark == mark) {
            log.debug(s"Removing duplicate flow $flow")
            forgetFlow(flow)
            var flowsRemoved = 1
            if (flow.linkedFlow ne null) {
                deleter.removeFlowFromDatapath(flow.linkedFlow.flowMatch,
                                               flow.linkedFlow.sequence)
                forgetFlow(flow.linkedFlow)
                flowsRemoved += 1
            }
            metrics.dpFlowsRemovedMetric.mark(flowsRemoved)
        }
    }

    override def flowExists(mark: Int): Boolean = {
        val flow = indexToFlow(mark & mask)
        (flow ne null) && flow.mark == mark
    }

    override def shouldProcess = deleter.shouldProcess()

    override def process(): Unit = {
        deleter.processCompletedFlowOperations()
        val tick = clock.tick
        var flowId = expirationIndexer.pollForExpired(tick)
        while (flowId != ManagedFlow.NoFlow) {
            val flow = indexToFlow((flowId & mask).toInt)
            if (flow != null && flow.id == flowId) {
                removeFlow(flow)
            }
            flowId = expirationIndexer.pollForExpired(tick)
        }
    }

    override def invalidateFlowsFor(tag: FlowTag): Unit = {
        val iter = tagIndexer.invalidateFlowsFor(tag)
        while (iter.hasNext()) {
            removeFlow(iter.next())
        }
    }

    private def registerFlow(flow: ManagedFlowImpl): Unit = {
        indexFlow(flow)
        expirationIndexer.enqueueFlowExpiration(flow.id,
                                                flow.absoluteExpirationNanos,
                                                flow.expirationType)
        tagIndexer.indexFlowTags(flow)

        meters.trackFlow(flow.flowMatch, flow.tags)
        var flowsAdded = 1
        if (flow.linkedFlow ne null) {
            indexFlow(flow.linkedFlow)
            flowsAdded += 1
        }
        metrics.dpFlowsMetric.mark(flowsAdded)
    }

    private def removeFlow(flow: ManagedFlowImpl): Unit = {
        deleter.removeFlowFromDatapath(flow.flowMatch, flow.sequence)
        forgetFlow(flow)
        var flowsRemoved = 1
        if (flow.linkedFlow ne null) {
            deleter.removeFlowFromDatapath(flow.linkedFlow.flowMatch,
                                           flow.linkedFlow.sequence)
            forgetFlow(flow.linkedFlow)
            flowsRemoved += 1
        }
        metrics.dpFlowsRemovedMetric.mark(flowsRemoved)
    }

    private def forgetFlow(flow: ManagedFlowImpl): Unit = {
        tagIndexer.removeFlowTags(flow)
        clearFlowIndex(flow)
        flow.callbacks.runAndClear()
        flow.unref()
    }

    private def indexFlow(flow: ManagedFlowImpl): Unit = {
        numFlows += 1
        ensureCapacity()
        var index = 0
        do {
            curIndex += 1
            index = curIndex & mask
        } while (indexToFlow(index) ne null)
        indexToFlow(index) = flow
        flow.setId(curIndex)
        flow.setMark((curIndex & IndexMask) | (workerId << IndexShift))
    }

    private def clearFlowIndex(flow: ManagedFlowImpl): Unit = {
        indexToFlow(flow.mark & mask) = null
        numFlows -= 1
    }

    private def ensureCapacity(): Unit = {
        if (numFlows > indexToFlow.length) {
            val newIndexToFlow = new Array[ManagedFlowImpl](indexToFlow.length * 2)
            Array.copy(indexToFlow, 0, newIndexToFlow, 0, indexToFlow.length)
            indexToFlow = newIndexToFlow
            mask = indexToFlow.length - 1
        }
    }
}

class FlowControllerDeleterImpl(flowProcessor: FlowProcessor,
                                datapathId: Int,
                                meters: MeterRegistry)
        extends FlowControllerDeleter with MidolmanLogging {
    private val completedFlowOperations = new SpscArrayQueue[FlowOperation](
        flowProcessor.capacity)
    private val pooledFlowOperations = new ArrayObjectPool[FlowOperation](
        flowProcessor.capacity, new FlowOperation(_, completedFlowOperations))
    private val flowRemoveCommandsToRetry = new ArrayList[FlowOperation](
        flowProcessor.capacity)

    override def removeFlowFromDatapath(flowMatch: FlowMatch,
                                        sequence: Long): Unit = {
        log.debug(s"Removing flow $flowMatch($sequence) from datapath")
        val flowOp = takeFlowOperation(flowMatch, sequence)
        // Spin while we try to eject the flow. This can happen if we invalidated
        // a flow so close to its creation that it has not been created yet.
        while (!flowProcessor.tryEject(sequence, datapathId,
                                       flowMatch, flowOp)) {
            processCompletedFlowOperations()
            Thread.`yield`()
        }
    }

    override def processCompletedFlowOperations(): Unit = {
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

    override def shouldProcess(): Boolean = completedFlowOperations.size > 0

    private def retryFailedFlowOperations(): Unit = {
        var i = 0
        while (i < flowRemoveCommandsToRetry.size()) {
            val cmd = flowRemoveCommandsToRetry.get(i)
            val fmatch = cmd.flowMatch
            val seq = cmd.sequence
            flowProcessor.tryEject(seq, datapathId, fmatch, cmd)
            i += 1
        }
        flowRemoveCommandsToRetry.clear()
    }

    private def flowDeleteFailed(req: FlowOperation): Unit = {
        log.debug("Got an exception when trying to remove " +
                  s"${req.flowMatch}", req.failure)
        req.netlinkErrorCode match {
            case EBUSY | EAGAIN | EIO | EINTR | ETIMEOUT if req.retries > 0 =>
                scheduleRetry(req)
                return
            case ENODEV | ENOENT | ENXIO =>
                log.debug(s"${req.flowMatch} was already deleted")
            case _ =>
                log.error(s"Failed to delete ${req.flowMatch}", req.failure)
        }
        meters.forgetFlow(req.flowMatch)
        req.clear()
    }

    private def scheduleRetry(req: FlowOperation): Unit = {
        req.retries = (req.retries - 1).toByte
        req.failure = null
        log.debug(s"Scheduling retry of flow ${req.flowMatch}")
        flowRemoveCommandsToRetry.add(req)
    }

    private def flowDeleteSucceeded(req: FlowOperation): Unit = {
        val flowMetadata = req.flowMetadata
        val flowMatch = req.flowMatch
        log.debug(s"DP confirmed removal of ${req.flowMatch}")
        meters.updateFlow(flowMatch, flowMetadata.getStats)
        meters.forgetFlow(flowMatch)
        req.clear()
    }

    private val flowOperationParkable = new Parkable {
        override def shouldWakeUp() = completedFlowOperations.size > 0
    }

    private def takeFlowOperation(flowMatch: FlowMatch,
                                  sequence: Long): FlowOperation = {
        var flowOp: FlowOperation = null
        while ({ flowOp = pooledFlowOperations.take; flowOp } eq null) {
            processCompletedFlowOperations()
            if (pooledFlowOperations.available == 0) {
                log.debug("Parking until the pending flow operations complete")
                flowOperationParkable.park()
            }
        }
        flowOp.reset(flowMatch, sequence, retries = 10)
        flowOp
    }
}
