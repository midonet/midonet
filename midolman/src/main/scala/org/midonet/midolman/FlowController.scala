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

import java.util.{HashMap, ArrayList}

import akka.actor.{Actor, ActorSystem}

import org.jctools.queues.SpscArrayQueue

import com.codahale.metrics.Gauge
import org.midonet.ErrorCode._

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.FlowProcessor
import org.midonet.midolman.flows.{FlowIndexer, FlowTagIndexer}
import org.midonet.midolman.flows.FlowExpirationIndexer.Expiration
import org.midonet.midolman.flows._
import org.midonet.midolman.management.Metering
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.monitoring.MeterRegistry
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.FlowMatch
import org.midonet.util.collection.{NoOpPool, ArrayObjectPool}
import org.midonet.util.concurrent.{Backchannel, NanoClock}
import org.midonet.util.concurrent.WakerUpper.Parkable

trait FlowController extends FlowIndexer with FlowTagIndexer
                     with FlowExpirationIndexer with Backchannel { this: Actor =>

    protected val config: MidolmanConfig
    protected val clock: NanoClock
    protected val flowProcessor: FlowProcessor
    protected val datapathId: Int
    val metrics: PacketPipelineMetrics

    implicit val system: ActorSystem

    val maxFlows = ((config.datapath.maxFlowCount / config.simulationThreads) * 1.2).toInt

    val meters = new MeterRegistry(maxFlows)
    Metering.registerAsMXBean(meters)

    private val managedFlowPool = new ArrayObjectPool[ManagedFlow](
        maxFlows, new ManagedFlow(_))
    private val oversubscriptionManagedFlowPool = new NoOpPool[ManagedFlow](
        new ManagedFlow(_))
    private val completedFlowOperations = new SpscArrayQueue[FlowOperation](
        flowProcessor.capacity)
    private val pooledFlowOperations = new ArrayObjectPool[FlowOperation](
        flowProcessor.capacity, new FlowOperation(self, _, completedFlowOperations))
    private val flowRemoveCommandsToRetry = new ArrayList[FlowOperation](
        flowProcessor.capacity)

    private val dpFlows = new HashMap[FlowMatch, ManagedFlow](maxFlows)

    metrics.currentDpFlowsMetric register new Gauge[Long] {
        override def getValue = dpFlows.size()
    }

    def tryAddFlow(context: PacketContext, expiration: Expiration): Boolean = {
        val flowMatch = context.origMatch
        val callbacks = context.flowRemovedCallbacks
        if (!dpFlows.containsKey(flowMatch)) {
            var flow = managedFlowPool.take
            if (flow eq null)
                flow = oversubscriptionManagedFlowPool.take
            flow.reset(flowMatch, context.flowTags, callbacks, 0L, expiration, clock.tick)
            registerFlow(flow)
            context.flow = flow
            context.log.debug(s"Added flow $flow")
            true
        } else {
            context.log.debug(s"Tried to add duplicate flow for $flowMatch")
            callbacks.runAndClear()
            false
        }
    }

    override def shouldProcess() = completedFlowOperations.size > 0

    override def process(): Unit = {
        processCompletedFlowOperations()
        checkFlowsExpiration(clock.tick)
    }

    override def registerFlow(flow: ManagedFlow): Unit = {
        super.registerFlow(flow)
        dpFlows.put(flow.flowMatch, flow)
        meters.trackFlow(flow.flowMatch, flow.tags)
        metrics.dpFlowsMetric.mark()
        flow.ref()
    }

    override def removeFlow(flow: ManagedFlow): Unit = {
        val removedFlow = dpFlows.remove(flow.flowMatch)
        if (removedFlow eq flow) {
            super.removeFlow(flow)
            flow.callbacks.runAndClear()
            removeFlowFromDatapath(flow)
            flow.unref()
        } else if (removedFlow ne null) {
            dpFlows.put(removedFlow.flowMatch, removedFlow)
        }
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
}
