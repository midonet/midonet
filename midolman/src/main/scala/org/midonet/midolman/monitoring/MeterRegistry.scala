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
package org.midonet.midolman.monitoring

import java.util.concurrent.ConcurrentHashMap
import java.util.{ArrayList, Collection, Collections, List, HashMap => JHashMap}

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.odp.FlowMatch
import org.midonet.odp.flows.FlowStats
import org.midonet.sdn.flows.FlowTagger.{FlowTag, MeterTag}
import org.midonet.util.collection.ArrayObjectPool
import org.midonet.management.{FlowStats => JmxFlowStats}

object MeterRegistry {
    def newOnHeap(maxFlows: Int): MeterRegistry =
        new OnHeapMeterRegistry(maxFlows)
    def newOffHeap(): MeterRegistry = new DummyMeterRegistry
}

trait MeterRegistry {
    def getMeterKeys(): Collection[String]
    def getMeter(key: String): JmxFlowStats
    def trackFlow(flowMatch: FlowMatch, tags: List[FlowTag]): Unit
    def recordPacket(packetLen: Int, tags: List[FlowTag]): Unit
    def updateFlow(flowMatch: FlowMatch, stats: FlowStats): Unit
    def forgetFlow(flowMatch: FlowMatch): Unit
}

class OnHeapMeterRegistry(val maxFlows: Int) extends MeterRegistry {
    val log = Logger(LoggerFactory.getLogger("org.midonet.metering"))

    class FlowData {
        val meters = new ArrayList[MeterTag](8)
        val stats = new FlowStats()

        def reset() {
            stats.bytes = 0
            stats.packets = 0
            meters.clear()
        }
    }

    private val metadataPool = new ArrayObjectPool[FlowData]((maxFlows * 1.1).toInt,
                                                              pool => new FlowData())

    private val meters = new ConcurrentHashMap[String, JmxFlowStats]()
    private val trackedFlows = new JHashMap[FlowMatch, FlowData]()
    private val DELTA = new FlowStats()

    override def getMeterKeys(): Collection[String] = meters.keySet

    override def getMeter(key: String): JmxFlowStats = meters.get(key)

    override def trackFlow(flowMatch: FlowMatch, tags: List[FlowTag]): Unit = {
        if (trackedFlows.containsKey(flowMatch))
            return

        var metadata = metadataPool.take
        if (metadata eq null)
            metadata = metadataPool.factory(metadataPool)

        metadata.reset()

        var i = 0
        while (i < tags.size()) {
            tags.get(i) match {
                case meter: MeterTag =>
                    metadata.meters add meter
                    if (meters.containsKey(meter.meterName)) {
                        log.debug(s"tracking a new flow for meter ${meter.meterName}")
                    } else {
                        meters.put(meter.meterName, new JmxFlowStats())
                        log.debug(s"discovered a new meter: ${meter.meterName}")
                    }
                case _ => // Do nothing
            }
            i += 1
        }

        log.debug(s"new flow is associated with ${metadata.meters.size} meters")
        if (metadata.meters.size() > 0)
            trackedFlows.put(flowMatch, metadata)
        else
            metadataPool.offer(metadata)
    }

    override def recordPacket(packetLen: Int, tags: List[FlowTag]): Unit = {
        DELTA.packets = 1
        DELTA.bytes = packetLen

        var i = 0
        while (i < tags.size()) {
            tags.get(i) match {
                case meter: MeterTag =>
                    if (meters.containsKey(meter.meterName)) {
                        log.debug(s"adding a packet to meter: ${meter.meterName}")
                        meters.get(meter.meterName).add(DELTA.packets, DELTA.bytes)
                    } else {
                        meters.put(meter.meterName, new JmxFlowStats(DELTA.packets, DELTA.bytes))
                        log.debug(s"discovered a new meter: ${meter.meterName}")
                    }
                case _ => // Do nothing
            }
            i += 1
        }
    }

    override def updateFlow(flowMatch: FlowMatch, stats: FlowStats): Unit = {
        val metadata = trackedFlows.get(flowMatch)
        if (metadata ne null) {
            metadata.stats.updateAndGetDelta(stats, DELTA)
            if (DELTA.packets < 0) {
                metadata.stats.packets = 0
                metadata.stats.bytes = 0
                metadata.stats.updateAndGetDelta(stats, DELTA)
            }
            var i = 0
            while (i < metadata.meters.size()) {
                val meterName = metadata.meters.get(i).meterName
                log.debug(s"meter $meterName got ${DELTA.bytes} bytes / ${DELTA.packets} packets")
                meters.get(meterName).add(DELTA.packets, DELTA.bytes)
                i += 1
            }
        }
    }

    override def forgetFlow(flowMatch: FlowMatch) {
        val metadata = trackedFlows.remove(flowMatch)
        if (metadata ne null)
            metadataPool.offer(metadata)
    }
}

class DummyMeterRegistry extends MeterRegistry {
    override def getMeterKeys(): Collection[String] = Collections.emptySet[String]()
    override def getMeter(key: String): JmxFlowStats = null
    override def trackFlow(flowMatch: FlowMatch, tags: List[FlowTag]): Unit = {}
    override def recordPacket(packetLen: Int, tags: List[FlowTag]): Unit = {}
    override def updateFlow(flowMatch: FlowMatch, stats: FlowStats): Unit = {}
    override def forgetFlow(flowMatch: FlowMatch): Unit = {}
}
