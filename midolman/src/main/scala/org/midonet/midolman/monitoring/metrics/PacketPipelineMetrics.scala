/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.midolman.monitoring.metrics

import java.util.concurrent.TimeUnit

import com.yammer.metrics.core.MetricsRegistry
import com.yammer.metrics.core.Gauge

import org.midonet.util.throttling.ThrottlingGuard

class PacketPipelineMetrics(val registry: MetricsRegistry,
                            val throttler: ThrottlingGuard) {

    val pendedPackets = registry.newCounter(
        classOf[PacketPipelineGauge], "currentPendedPackets")

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

    val packetsProcessed = registry.newMeter(
        classOf[PacketPipelineMeter],
        "packetsProcessed", "packets",
        TimeUnit.SECONDS)

    // FIXME(guillermo)
    //  - make this a meter
    //  - the throttler needs to expose a callback
    val packetsDropped = registry.newGauge(
        classOf[PacketPipelineCounter],
        "packetsDropped",
        new Gauge[Long]{
            override def value = throttler.numDroppedTokens()
        })

    val liveSimulations = registry.newGauge(
        classOf[PacketPipelineGauge],
        "liveSimulations",
        new Gauge[Long]{
            override def value = throttler.numTokens()
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
}
