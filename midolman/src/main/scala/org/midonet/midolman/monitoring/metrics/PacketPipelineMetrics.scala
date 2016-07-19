/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.monitoring.metrics

import java.util.concurrent.TimeUnit

import com.codahale.metrics.{Clock, Gauge, MetricRegistry, Timer}
import com.codahale.metrics.MetricRegistry.name

import org.midonet.util.metrics.HdrHistogramSlidingTimeWindowReservoir

class PacketPipelineMetrics(val registry: MetricRegistry, workerId: Int) {
    val workerTag = s"worker-$workerId"

    val packetsOnHold = registry.counter(
        name(classOf[PacketPipelineCounter], workerTag, "packetsOnHold"))

    val packetsPostponed = registry.meter(
        name(classOf[PacketPipelineMeter], workerTag,
             "packetsPostponed", "packets"))

    val contextsAllocated = registry.counter(name(
        classOf[PacketPipelineCounter], "contextsAllocated", "contexts"))

    val contextsPooled = registry.counter(name(
        classOf[PacketPipelineCounter], "contextsPooled", "contexts"))

    val contextsBeingProcessed = registry.counter(name(
        classOf[PacketPipelineCounter], "contextsBeingProcessed", "contexts"))

    val packetsDropped = registry.meter(
        name(classOf[PacketPipelineMeter], workerTag,
             "packetsDropped", "packets"))

    val statePacketsProcessed = registry.meter(
        name(classOf[PacketPipelineMeter], workerTag,
             "statePacketsProcessed", "packets"))

    val packetsProcessed = registry.register(
        name(classOf[PacketPipelineHistogram], workerTag, "packetsProcessed"),
        new Timer(new HdrHistogramSlidingTimeWindowReservoir(
                      5, TimeUnit.MINUTES, 10, TimeUnit.SECONDS,
                      Clock.defaultClock)))

    val currentDpFlowsMetric = registry.register(
        name(classOf[FlowTablesGauge], workerTag, "currentDatapathFlows"),
        new Gauge[Long] {
            override def getValue: Long =
                dpFlowsMetric.getCount - dpFlowsRemovedMetric.getCount
    })

    val dpFlowsMetric = registry.meter(
        name(classOf[FlowTablesMeter], workerTag,
             "datapathFlowsCreated", "datapathFlows"))

    val dpFlowsRemovedMetric = registry.meter(
        name(classOf[FlowTablesMeter], workerTag,
             "datapathFlowsRemoved", "datapathFlowsRemoved"))

    val workerQueueOverflow = registry.meter(
        name(classOf[PacketPipelineMeter], workerTag,
             "packetQueue", "overflow"))

    def packetPostponed() {
        packetsPostponed.mark()
        packetsOnHold.inc()
    }
}

class PacketExecutorMetrics(val registry: MetricRegistry, executorId: Int) {
    val executorTag = s"executor-$executorId"

    val packetsExecuted = registry.register(
        name(classOf[PacketPipelineHistogram], executorTag, "packetsExecuted"),
        new Timer(new HdrHistogramSlidingTimeWindowReservoir(
                      5, TimeUnit.MINUTES, 10, TimeUnit.SECONDS,
                      Clock.defaultClock)))
}
