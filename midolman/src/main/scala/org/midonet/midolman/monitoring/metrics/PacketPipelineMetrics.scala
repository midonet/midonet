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

class PacketPipelineMetrics(val registry: MetricRegistry) {

    val pendedPackets = registry.counter(name(
        classOf[PacketPipelineGauge], "currentPendedPackets"))

    val packetsOnHold = registry.counter(name(
        classOf[PacketPipelineMeter], "packetsOnHold"))

    val wildcardTableHits = registry.meter(name(
        classOf[PacketPipelineMeter], "wildcardTableHits", "packets"))

    val packetsToPortSet = registry.meter(name(
        classOf[PacketPipelineMeter], "packetsToPortSet", "packets"))

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

    val wildcardTableHitLatency = registry.histogram(name(
        classOf[PacketPipelineHistogram], "wildcardTableHitLatency"))

    val packetToPortSetLatency = registry.histogram(name(
        classOf[PacketPipelineHistogram], "packetToPortSetLatency"))

    val simulationLatency = registry.histogram(name(
        classOf[PacketPipelineHistogram], "simulationLatency"))

    val wildcardTableHitAccumulatedTime = registry.counter(name(
        classOf[PacketPipelineAccumulatedTime],
        "wildcardTableHitAccumulatedTime"))

    val packetToPortSetAccumulatedTime = registry.counter(name(
        classOf[PacketPipelineAccumulatedTime],
        "packetToPortSetAccumulatedTime"))

    val simulationAccumulatedTime = registry.counter(name(
        classOf[PacketPipelineAccumulatedTime],
        "simulationAccumulatedTime"))

    def wildcardTableHit(latency: Int) {
        wildcardTableHits.mark()
        wildcardTableHitLatency.update(latency)
        wildcardTableHitAccumulatedTime.inc(latency)
    }

    def packetToPortSet(latency: Int) {
        packetsToPortSet.mark()
        packetToPortSetLatency.update(latency)
        packetToPortSetAccumulatedTime.inc(latency)
    }

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
