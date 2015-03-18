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

package org.midonet.midolman.monitoring.metrics

import com.codahale.metrics.{Gauge, MetricRegistry}
import com.codahale.metrics.MetricRegistry.name
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics.CompositeLongGauge

object PacketPipelineMetrics {
    class CompositeLongGauge(capacity: Int) extends Gauge[Long] {
        private val gauges = new Array[Gauge[Long]](capacity)

        def register(gauge: Gauge[Long], index: Int): Unit =
            gauges(index) = gauge

        override def getValue: Long = {
            var value = 0L
            var i = 0
            while (i < gauges.length) {
                value += gauges(i).getValue
                i += 1
            }
            value
        }
    }
}
class PacketPipelineMetrics(val registry: MetricRegistry,
                            numPipelineProcessors: Int) {

    val pendedPackets = registry.counter(name(
        classOf[PacketPipelineGauge], "currentPendedPackets"))

    val packetsOnHold = registry.counter(name(
        classOf[PacketPipelineMeter], "packetsOnHold"))

    val packetsSimulated = registry.meter(name(
        classOf[PacketPipelineMeter], "packetsSimulated", "packets"))

    val packetsPostponed = registry.meter(name(
        classOf[PacketPipelineMeter], "packetsPostponed", "packets"))

    val packetsProcessed = registry.meter(name(
        classOf[PacketPipelineMeter], "packetsProcessed", "packets"))

    val packetsDropped = registry.meter(name(
        classOf[PacketPipelineCounter], "packetsDropped", "packets"))

    val liveSimulations = registry.register(name(
        classOf[PacketPipelineGauge], "liveSimulations"),
        new Gauge[Long]{ override def getValue = 0 })

    val simulationLatency = registry.histogram(name(
        classOf[PacketPipelineHistogram], "simulationLatency"))

    val simulationAccumulatedTime = registry.counter(name(
        classOf[PacketPipelineAccumulatedTime],
        "simulationAccumulatedTime"))

    val currentDpFlowsMetric = registry.register(name(
            classOf[FlowTablesGauge], "currentDatapathFlows"),
            new CompositeLongGauge(numPipelineProcessors))

    val dpFlowsMetric = registry.meter(name(
            classOf[FlowTablesMeter], "datapathFlowsCreated",
            "datapathFlows"))

    def packetSimulated(latency: Int) {
        packetsSimulated.mark()
        simulationLatency.update(latency)
        simulationAccumulatedTime.inc(latency)
    }

    def packetPostponed() {
        packetsPostponed.mark()
        packetsOnHold.inc()
    }
}
