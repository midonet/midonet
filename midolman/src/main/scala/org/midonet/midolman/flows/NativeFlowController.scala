/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.midolman.flows

import java.util.ArrayList

import org.midonet.Util
import org.midonet.odp.FlowMatch
import org.midonet.odp.FlowMatches
import org.midonet.midolman.CallbackRegistry
import org.midonet.midolman.CallbackRegistry.CallbackSpec
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.FlowProcessor
import org.midonet.midolman.{FlowController, FlowControllerDeleterImpl}
import org.midonet.midolman.PacketWorkersService
import org.midonet.midolman.flows.{NativeFlowControllerJNI => JNI}
import org.midonet.midolman.flows.FlowExpirationIndexer.Expiration
import org.midonet.midolman.monitoring.MeterRegistry
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.concurrent.NanoClock

object NativeFlowController {
    var loaded = false
    def loadNativeLibrary() = synchronized {
        if (!loaded) {
            System.loadLibrary("nativeFlowController")
            loaded = true
        }
    }
}

class NativeFlowController(config: MidolmanConfig,
                           clock: NanoClock,
                           flowProcessor: FlowProcessor,
                           datapathId: Int,
                           workerId: Int,
                           metrics: PacketPipelineMetrics,
                           meters: MeterRegistry,
                           cbRegistry: CallbackRegistry) extends FlowController {
    NativeFlowController.loadNativeLibrary()

    private val numWorkers = PacketWorkersService.numWorkers(config)
    private val maxFlows = Math.min(Util.findNextPositivePowerOfTwo(
                                        config.datapath.maxFlowCount / numWorkers),
                                    FlowController.IndexMask)
    private val flowTable = JNI.createFlowTable(maxFlows)
    private val indexer = JNI.createFlowTagIndexer()
    private val expirer = JNI.createFlowExpirationIndexer()
    private val deleter = new FlowControllerDeleterImpl(flowProcessor,
                                                        datapathId,
                                                        meters)

    override def addFlow(fmatch: FlowMatch, flowTags: ArrayList[FlowTag],
                         removeCallbacks: ArrayList[CallbackSpec],
                         expiration: Expiration): ManagedFlow = {
        ensureSpace(1)

        val flow = addFlow(fmatch, expiration)
        flow.addCallbacks(removeCallbacks)
        flow.addTags(flowTags)
        metrics.dpFlowsMetric.mark(1)
        flow
    }

    override def addRecircFlow(fmatch: FlowMatch,
                               recircMatch: FlowMatch,
                               flowTags: ArrayList[FlowTag],
                               removeCallbacks: ArrayList[CallbackSpec],
                               expiration: Expiration): ManagedFlow = {
        ensureSpace(2)
        val flow = addFlow(recircMatch, expiration)
        val outerFlow = addFlow(fmatch, expiration)
        flow.setLinkedId(outerFlow.id)
        flow.addCallbacks(removeCallbacks)
        flow.addTags(flowTags)
        outerFlow.setLinkedId(flow.id)
        metrics.dpFlowsMetric.mark(2)
        flow
    }

    override def removeDuplicateFlow(mark: Int): Unit = {
        val flow = flowForMark(mark)
        if (flow != null) {
            val linkedId = flow.linkedId
            flow.forget()
            metrics.dpFlowsRemovedMetric.mark(1)
            if (linkedId >= 0) {
                removeFlow(linkedId)
            }
        }
    }

    override def flowExists(mark: Int): Boolean = {
        val index = mark & FlowController.IndexMask
        val id = JNI.flowTableIdAtIndex(flowTable, index)
        id >= 0
    }

    override def invalidateFlowsFor(tag: FlowTag): Unit = {
        val invalid = JNI.flowTagIndexerInvalidate(indexer, tag.toLongHash)
        try {
            val count = JNI.flowTagIndexerInvalidFlowsCount(invalid)
            var i = 0
            while (i < count) {
                val id = JNI.flowTagIndexerInvalidFlowsGet(invalid, i)
                removeFlow(id)
                i += 1
            }
        } finally {
            JNI.flowTagIndexerInvalidFlowsFree(invalid)
        }
    }

    override def shouldProcess: Boolean = deleter.shouldProcess()

    override def process(): Unit = {
        deleter.processCompletedFlowOperations()
        val now = clock.tick
        var flowId = JNI.flowExpirationIndexerPollForExpired(expirer, now)
        while (flowId != ManagedFlow.NoFlow) {
            removeFlow(flowId)
            flowId = JNI.flowExpirationIndexerPollForExpired(expirer, now)
        }
    }

    private def addFlow(flowMatch: FlowMatch, expiration: Expiration)
    : NativeManagedFlow = {
        val id = JNI.flowTablePutFlow(flowTable, FlowMatches.toBytes(flowMatch))
        JNI.flowExpirationIndexerEnqueueFlowExpiration(
            expirer, id, clock.tick + expiration.value, expiration.typeId)
        new NativeManagedFlow(id)
    }

    private def removeFlow(id: Long): Unit = {
        val flow = flowForId(id)
        if (flow != null) {
            deleter.removeFlowFromDatapath(flow.flowMatch, flow.sequence)
            val linkedId = flow.linkedId
            flow.forget()

            if (linkedId >= 0) {
                removeFlow(linkedId)
            }
            metrics.dpFlowsRemovedMetric.mark(1)
        }
    }

    private def flowForId(id: Long): NativeManagedFlow = {
        val index = (id & FlowController.IndexMask).toInt
        val idInTable = JNI.flowTableIdAtIndex(flowTable, index)
        if (idInTable >= 0 && id == idInTable) {
            new NativeManagedFlow(id)
        } else {
            null
        }
    }

    private def flowForMark(mark: Int): NativeManagedFlow = {
        val index = mark & FlowController.IndexMask
        val id = JNI.flowTableIdAtIndex(flowTable, index)
        if (id >= 0) {
            new NativeManagedFlow(id)
        } else {
            null
        }
    }

    private[flows] def ensureSpace(count: Int): Unit = {
        while (JNI.flowTableOccupied(flowTable) + count > maxFlows) {
            val toEvict = JNI.flowExpirationIndexerEvictFlow(expirer)
            removeFlow(toEvict)
        }
    }

    class NativeManagedFlow(val id: Long) extends ManagedFlow {
        override val flowMatch: FlowMatch =
            FlowMatches.fromBytes(JNI.flowTableFlowMatch(flowTable, id))

        override def mark: Int = ((id & FlowController.IndexMask).toInt |
                                      (workerId << FlowController.IndexShift))
        override def sequence: Long = JNI.flowTableFlowSequence(flowTable, id)
        override def assignSequence(seq: Long): Unit =
            JNI.flowTableFlowSetSequence(flowTable, id, sequence)

        def linkedId: Long = JNI.flowTableFlowLinkedId(flowTable, id)
        def setLinkedId(linkedId: Long): Unit =
            JNI.flowTableFlowSetLinkedId(flowTable, id, linkedId)

        def callbacks(): ArrayList[CallbackSpec] = {
            val count = JNI.flowTableFlowCallbackCount(flowTable, id)
            val callbacks = new ArrayList[CallbackSpec](count)
            var i = 0
            while (i < count) {
                val cbid = JNI.flowTableFlowCallbackId(flowTable, id, i)
                val args = JNI.flowTableFlowCallbackArgs(flowTable, id, i)
                callbacks.add(i, new CallbackSpec(cbid, args))
                i += 1
            }
            callbacks
        }

        def addCallbacks(callbacks: ArrayList[CallbackSpec]): Unit = {
            var i = 0
            while (i < callbacks.size) {
                val spec = callbacks.get(i)
                JNI.flowTableFlowAddCallback(flowTable, id,
                                             spec.id, spec.args)
                i += 1
            }
        }

        def addTags(tags: ArrayList[FlowTag]): Unit = {
            val tagsArray = new Array[Long](tags.size)
            var i = 0
            while (i < tagsArray.length) {
                tagsArray(i) = tags.get(i).toLongHash
                i += 1
            }
            JNI.flowTagIndexerIndexFlowTags(indexer, id, tagsArray)
        }

        def forget(): Unit = {
            JNI.flowTagIndexerRemoveFlow(indexer, id)
            cbRegistry.runAndClear(callbacks())
            JNI.flowTableClearFlow(flowTable, id)
        }
    }
}

