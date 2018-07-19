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

import java.io.BufferedWriter
import java.lang.management._
import java.util
import java.util.Map.Entry
import java.util.function.Consumer
import javax.management._

import com.google.common.annotations.VisibleForTesting
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import org.midonet.management.{FlowMeters, FlowStats, MeteringMXBean}
import org.midonet.midolman.monitoring.MeterRegistry
import org.midonet.util.StringUtil

trait PrometheusMetering {
    protected def registries: List[MeterRegistry]
    protected def currentTimeMillis: Long

    private final val metrics = Map(
        "midonet_packets_total" ->
            (((m: FlowStats) => m.getPackets),
             "Total number of packets processed."),
        "midonet_packets_bytes" ->
            (((m: FlowStats) => m.getBytes),
             "Total number of bytes processed.")
    )

    def toPrometheusMetrics(writer: BufferedWriter) : Unit = {
        // "Text Format" described in:
        // https://prometheus.io/docs/instrumenting/exposition_formats/
        // See also:
        // https://prometheus.io/docs/practices/naming/

        val now = currentTimeMillis

        metrics.foreach { case (metricsName, desc) =>
            val (getter, help) = desc

            // # HELP name help text
            // # TYPE name type
            writer.append("# HELP ")
            writer.append(metricsName)
            writer.append(" ")
            writer.append(help)
            writer.append("\n")
            writer.append("# TYPE ")
            writer.append(metricsName)
            writer.append(" counter\n")

            var i = 0
            while (i < registries.length) {
                val keys = registries(i).getMeterKeys.iterator
                while (keys.hasNext) {
                    val key = keys.next()
                    val meter = registries(i).getMeter(key)

                    // produce a line like:
                    //   midonet_packets_total{key="...",registry="1"} 1027 1530166440000
                    // REVISIT: Keys like "meters:tunnel:167772169:167772170"
                    // are not very convenient for users.  It would be more
                    // useful if we decomposite keys to IDs, IP addresses, etc.
                    writer.append(metricsName)
                    writer.append("{key=\"")
                    writer.append(key)
                    writer.append("\",registry=\"")
                    StringUtil.append(writer, i).append("\"} ")
                    StringUtil.append(writer, getter(meter)).append(' ')
                    StringUtil.append(writer, now).append('\n')
                }
                i += 1
            }
        }
    }
}

object Metering extends PrometheusMetering with MeteringMXBean {
    private val Log =
        Logger(LoggerFactory.getLogger("org.midonet.midolman.management"))

    protected var registries = List[MeterRegistry]()
    @volatile private var flowMeters: Array[FlowMeters] = _

    override protected def currentTimeMillis = System.currentTimeMillis()

    override def listMeters = {
        val keys = new util.HashSet[String]
        registries foreach { keys addAll _.getMeterKeys() }
        keys.toArray(new Array[String](keys.size()))
    }

    override def getMeter(name: String) = {
        registries.foldLeft(new FlowStats()) { (acc, r) =>
            val meter = r.getMeter(name)
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
                val regMeters = new util.HashMap[String, FlowStats]
                val keys = r(index).getMeterKeys.iterator
                while (keys.hasNext) {
                    val key = keys.next()
                    val value = r(index).getMeter(key)
                    if (value != null) {
                        regMeters.put(key, value)
                    }
                }
                meters(index) = new FlowMeters(index, regMeters)
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
            val registry = iterator.next()
            val keys = registry.getMeterKeys.iterator
            while (keys.hasNext) {
                val key = keys.next
                val value = registry.getMeter(key)
                val stats = meters.get(key)
                if (stats eq null) {
                    meters.put(key, new FlowStats(value.getPackets,
                                                  value.getBytes))
                } else {
                    stats.add(value)
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

    def reset(): Unit = {
        registries = List()
    }

    def toTextTable(writer: BufferedWriter, delim: Char = '\t') : Unit = {
        var i = 0

        while (i < registries.length) {
            val keys = registries(i).getMeterKeys.iterator
            while (keys.hasNext) {
                val key = keys.next()
                val meter = registries(i).getMeter(key)
                serializeMeter(key, meter.getPackets, meter.getBytes, writer, delim)
            }
            i += 1
        }
    }

    def serializeMeter(key: String,
                       packets: Long,
                       bytes: Long,
                       writer: BufferedWriter,
                       delim: Char) : Unit = {
        writer.append(key).append(delim)
        StringUtil.append(writer, packets).append(delim)
        StringUtil.append(writer, bytes).append('\n')
    }
}

class MeteringHTTPHandler extends SimpleHTTPServer.Handler {
    override def path: String = "/device_stats"
    def writeResponse(writer: BufferedWriter): Unit = {
        Metering.toTextTable(writer)
    }
}

class PrometheusMetricsHTTPHandler extends SimpleHTTPServer.Handler {
    override def path: String = "/metrics"
    def writeResponse(writer: BufferedWriter): Unit = {
        Metering.toPrometheusMetrics(writer)
    }
}
