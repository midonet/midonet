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
package org.midonet.midolman.management

import java.lang.management._
import java.util

import javax.management._

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.management.{FlowMeters, FlowStats, MeteringMXBean}
import org.midonet.midolman.monitoring.MeterRegistry

object Metering extends MeteringMXBean {
    private val Log =
        Logger(LoggerFactory.getLogger("org.midonet.midolman.management"))

    private var registries = List[MeterRegistry]()
    @volatile private var flowMeters: Array[FlowMeters] = _

    override def listMeters = {
        val keys = new util.HashSet[String]
        registries foreach { keys addAll _.meters.keySet() }
        keys.toArray(new Array[String](keys.size()))
    }

    override def getMeter(name: String) = {
        registries.foldLeft(new FlowStats()) { (acc, r) =>
            val meter = r.meters.get(name)
            if (meter ne null)
                acc.add(meter)
            acc
        }
    }

    override def getMeters: Array[FlowMeters] = {
        var meters = flowMeters
        val r = registries
        if ((meters eq null) || meters.length < r.length) {
            meters = new Array[FlowMeters](r.length)
            var index = 0
            while (index < meters.length) {
                meters(index) = new FlowMeters(index, r(index).meters)
                index += 1
            }
            flowMeters = meters
        }
        meters
    }

    override def getConsolidatedMeters: FlowMeters = {
        val iterator = registries.iterator
        val meters = new util.HashMap[String, FlowStats]()
        while (iterator.hasNext) {
            val i = iterator.next().meters.entrySet().iterator()
            while (i.hasNext) {
                val entry = i.next()
                val stats = meters.get(entry.getKey)
                if (stats eq null) {
                    meters.put(entry.getKey, new FlowStats(
                        entry.getValue.getPackets,
                        entry.getValue.getBytes))
                } else {
                    stats.add(entry.getValue)
                }
            }
        }
        new FlowMeters(-1, meters)
    }

    def registerAsMXBean(meters: MeterRegistry) = this.synchronized {
        try {
            registries :+= meters
            if (registries.size == 1) {
                ManagementFactory.getPlatformMBeanServer.registerMBean(this,
                    new ObjectName(MeteringMXBean.NAME))
            }
        } catch {
            case e: Exception =>
                Log.error("Failed to register metering JMX bean", e)
        }
    }
}
