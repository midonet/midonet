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

import java.util.concurrent.TimeUnit

import com.yammer.metrics.core.MetricsRegistry
import com.yammer.metrics.core.Gauge

class PacketPipelineMetrics(val registry: MetricsRegistry) {

    val pendedPackets = registry.newCounter(
        classOf[PacketPipelineGauge], "currentPendedPackets")

    val packetsOnHold = registry.newCounter(
        classOf[PacketPipelineMeter], "packetsOnHold")

    val wildcardTableHits = registry.newMeter(
        classOf[PacketPipelineMeter],
        "wildcardTableHits", "packets",
        TimeUnit.SECONDS)

    val packetsToPortSet = registry.newMeter(
        classOf[PacketPipelineMeter],
        "packetsToPortSet", "packets",
        TimeUnit.SECONDS)

    val packetsSimulated = registry.newMeter(
        classOf[PacketPipelineMeter],
        "packetsSimulated", "packets",
        TimeUnit.SECONDS)

    val packetsPostponed = registry.newMeter(
        classOf[PacketPipelineMeter],
        "packetsPostponed", "packets",
        TimeUnit.SECONDS)

    val packetsProcessed = registry.newMeter(
        classOf[PacketPipelineMeter],
        "packetsProcessed", "packets",
        TimeUnit.SECONDS)

    val packetsDropped = registry.newMeter(
        classOf[PacketPipelineCounter],
        "packetsDropped", "packets",
        TimeUnit.SECONDS)

    val liveSimulations = registry.newGauge(
        classOf[PacketPipelineGauge],
        "liveSimulations",
        new Gauge[Long]{
            override def value = 0
        })

    val wildcardTableHitLatency = registry.newHistogram(
        classOf[PacketPipelineHistogram], "wildcardTableHitLatency")

    val packetToPortSetLatency = registry.newHistogram(
        classOf[PacketPipelineHistogram], "packetToPortSetLatency")

    val simulationLatency = registry.newHistogram(
        classOf[PacketPipelineHistogram], "simulationLatency")

    val wildcardTableHitAccumulatedTime = registry.newCounter(
        classOf[PacketPipelineAccumulatedTime], "wildcardTableHitAccumulatedTime")

    val packetToPortSetAccumulatedTime = registry.newCounter(
        classOf[PacketPipelineAccumulatedTime], "packetToPortSetAccumulatedTime")

    val simulationAccumulatedTime = registry.newCounter(
        classOf[PacketPipelineAccumulatedTime], "simulationAccumulatedTime")

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
