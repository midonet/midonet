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
package org.midonet.management

import java.lang.management.{GarbageCollectorMXBean, MemoryPoolMXBean}
import javax.management.remote.{JMXConnectorFactory, JMXServiceURL}
import javax.management.{JMX, MBeanServerConnection, ObjectName}

import com.codahale.metrics.JmxReporter.{JmxCounterMBean, JmxGaugeMBean, JmxHistogramMBean, JmxMeterMBean}
import org.rogach.scallop._

class StatSet(columns: List[Column]) {
    def printHeader() {
        val groupHeaderBuf = new StringBuilder()
        val headerBuf = new StringBuilder()
        for (col <- columns) {
            groupHeaderBuf.append(col.groupHeader)
            headerBuf.append(col.header)
        }
        System.out.println(groupHeaderBuf.toString())
        System.out.println(headerBuf.toString())
    }

    def printValues() {
        val buf = new StringBuilder()
        for (col <- columns) {
            buf.append(col.value)
        }
        System.out.println(buf.toString())
    }
}

trait Column {
    def width: Int
    def header: String
    def groupHeader: String = s"%${width}s".format("")
    def value: String
}

class ColumnGroup(name: String, columns: List[StatColumn]) extends Column {
    def width = {
        var w = 0
        for (col <- columns) {
            w += col.width
        }
        // Otherwise %0s format throws an exception
        Math.max(w, 1)
    }

    def header: String = {
        val buf = new StringBuilder()
        for (col <- columns) {
            buf.append(col.header)
        }
        buf.toString
    }

    def value: String = {
        val buf = new StringBuilder()
        for (col <- columns) {
            buf.append(col.value)
        }
        buf.toString
    }

    override def groupHeader: String = s"%${width}s".format(name)
}

class StatColumn(name: String, minWidth: Int, getter: => Double,
                 divisor: Int = 1) extends Column {
    val width = Math.max(minWidth, name.length + 1)
    def header = s"%${width}s".format(name)
    def value: String = s" %${width-1}d".format(getter.toLong / divisor)
}

object MidolmanMetricCatalog {

    val BASE = "metrics:name=org.midonet.midolman.monitoring.metrics"
    val DP_FLOWS_GAUGE = s"$BASE.FlowTablesGauge"
    val SIM_LATENCY = s"$BASE.PacketPipelineHistogram"

    val CMS_GC= "java.lang:type=GarbageCollector,name=G1 Old Generation"
    val NEW_GC= "java.lang:type=GarbageCollector,name=G1 Young Generation"

    val OLD_MEM = "java.lang:type=MemoryPool,name=G1 Old Gen"
    val SURVIVOR_MEM = "java.lang:type=MemoryPool,name=G1 Survivor Space"
    val EDEN_MEM = "java.lang:type=MemoryPool,name=G1 Eden Space"

    class AllMetrics(val mbsc: MBeanServerConnection) {

        val dpFlowsGauge = new Gauge(mbsc, DP_FLOWS_GAUGE,
                                     "currentDatapathFlows")
        val gc = new GC(mbsc)
        val oldmem = new Pool(mbsc, OLD_MEM)
        val survivormem = new Pool(mbsc, SURVIVOR_MEM)
        val edenmem = new Pool(mbsc, EDEN_MEM)

        val oldGroup = new ColumnGroup("old",
            List(new StatColumn("used", 6, oldmem.used),
                 new StatColumn("total", 6, oldmem.total)))

        val survivorGroup = new ColumnGroup("survivor",
            List(new StatColumn("used", 6, survivormem.used),
                 new StatColumn("total", 6, survivormem.total)))

        val edenGroup = new ColumnGroup("eden",
            List(new StatColumn("used", 5, edenmem.used),
                 new StatColumn("total", 5, edenmem.total)))

        def getColumns = List(
            new StatColumn("dpflows", 7, dpFlowsGauge.get),
            buildLatencies,
            new StatColumn("gc time", 8, gc.getDelta),
            edenGroup, survivorGroup, oldGroup)

        def run(delaySecs: Int = 0, count: Int = 1): Unit = {
            var iterations = if (delaySecs > 0) count else 1
            val stat = new StatSet(getColumns)
            stat.printHeader()
            do {
                stat.printValues()
                iterations -= 1
                if (delaySecs > 0 && iterations > 0) {
                    Thread.sleep(delaySecs * 1000)
                }
            } while (iterations > 0)
        }

        private def buildLatency(worker: Int): List[StatColumn] = {
            val beanName = SIM_LATENCY + s".worker-${worker}.packetsProcessed"
            val latency  = new Histogram(mbsc, beanName)
            List(new StatColumn(s"w-${worker}95th", 7, latency.get95th, 1000),
                 new StatColumn(s"w-${worker}99th", 7, latency.get99th, 1000),
                 new StatColumn(s"w-${worker}999th", 7, latency.get999th, 1000))
        }

        private def buildLatencies = {
            def buildLatenciesHelper(n: Int, numWorkers:Int):
            List[StatColumn] =
                if (n >= numWorkers) {
                    Nil
                } else {
                    buildLatency(n) ::: buildLatenciesHelper(n + 1, numWorkers)
                }
            var beanName = SIM_LATENCY + "*packetsProcessed"
            val objectPattern = new ObjectName(beanName)
            val numWorkers = mbsc.queryNames(objectPattern, null).size()
            new ColumnGroup("latency (microsecs)",
                            buildLatenciesHelper(0, numWorkers))
        }
    }

    class Pool(mbsc: MBeanServerConnection, name: String) {
        val bean = JMX.newMXBeanProxy(mbsc, new ObjectName(name),
                                      classOf[MemoryPoolMXBean])

        def used = bean.getUsage.getUsed / (1024*1024)
        def total = bean.getUsage.getCommitted / (1024*1024)
    }

    class GC(mbsc: MBeanServerConnection) {
        val cms = JMX.newMXBeanProxy(mbsc, new ObjectName(CMS_GC),
                                     classOf[GarbageCollectorMXBean])
        val parnew = JMX.newMXBeanProxy(mbsc, new ObjectName(NEW_GC),
                                        classOf[GarbageCollectorMXBean])

        var lastTime: Long = 0
        def getDelta: Long = {
            val prev = lastTime
            lastTime = cms.getCollectionCount + parnew.getCollectionTime
            lastTime - prev
        }

    }

    class Gauge(mbsc: MBeanServerConnection, beanBaseName: String,
                metricsName: String) {


        var beanName = beanBaseName + "*" + metricsName
        val objectPattern = new ObjectName(beanName)

        def get: Long = {
            val objectNames = mbsc.queryNames(objectPattern, null)
            var total:Long = 0
            for (name <- objectNames.toArray) {
                val proxy = JMX.newMBeanProxy(mbsc, name.asInstanceOf[ObjectName],
                                              classOf[JmxGaugeMBean], true)
                total = total + proxy.getValue.asInstanceOf[java.lang.Long]
            }
            total
        }
    }

    class Counter(mbsc: MBeanServerConnection, beanName: String) {
        val objectName = new ObjectName(beanName)
        val proxy = JMX.newMBeanProxy(mbsc, objectName, classOf[JmxCounterMBean], true)

        private var lastCount = 0L
        def get: Long = {
            lastCount = proxy.getCount
            lastCount
        }

        def getDelta: Long = {
            val prev = lastCount
            get - prev
        }
    }

    class Histogram(mbsc: MBeanServerConnection, beanName: String) {
        val objectName = new ObjectName(beanName)
        val proxy = JMX.newMBeanProxy(mbsc, objectName, classOf[JmxHistogramMBean], true)

        def get50th: Double = proxy.get50thPercentile()
        def get75th: Double = proxy.get75thPercentile()
        def get95th: Double = proxy.get95thPercentile()
        def get98th: Double = proxy.get98thPercentile()
        def get99th: Double = proxy.get99thPercentile()
        def get999th: Double = proxy.get999thPercentile()
    }

    class Meter(mbsc: MBeanServerConnection, beanName: String) {
        val objectName = new ObjectName(beanName)
        val proxy = JMX.newMBeanProxy(mbsc, objectName, classOf[JmxMeterMBean], true)

        private var lastCount = 0L
        def count: Long = {
            lastCount = proxy.getCount.asInstanceOf[java.lang.Long]
            lastCount
        }

        def countDelta: Long = {
            val prev = lastCount
            count - prev
        }

        def oneMRate: Double = proxy.getOneMinuteRate
        def fiveMRate: Double = proxy.getFiveMinuteRate
        def fifteenMRate: Double = proxy.getFifteenMinuteRate
    }
}

object MidolmanStat extends App {
    System.setProperty("logback.configurationFile", "logback-disabled.xml")

    private def connect(host: String, port: Int): MBeanServerConnection = {
        val url = new JMXServiceURL(
            s"service:jmx:rmi:///jndi/rmi://$host:$port/jmxrmi")
        val jmxc = JMXConnectorFactory.connect(url, null)

        jmxc.getMBeanServerConnection
    }

    val opts = new ScallopConf(args) {
        val port = opt[Int]("port", short = 'p', default = Option(7200),
                            descr = "JMX port",
                            required = true)
        val host = opt[String]("host", short = 'h', default = Option("localhost"),
                               descr = "Host")
        val delay = trailArg[Int](required = false, default = Some(0),
                                  descr = "delay between updates, in seconds. If no "+
                                          "delay is specified, only one report is printed.")
        val count = trailArg[Int](required = false, default = Some(Integer.MAX_VALUE),
                                  descr = "number of updates, defaults to infinity")

        printedName = "mm-stat"
        footer("Copyright (c) 2014 Midokura SARL, All Rights Reserved.")
    }

    try {
        val conn = connect(opts.host.get.get, opts.port.get.get)
        val metrics = new MidolmanMetricCatalog.AllMetrics(conn)
        metrics.run(opts.delay.get.get, opts.count.get.get)
    } catch { case e: Throwable =>
        var t = e
        while ((t.getCause ne null) && (t.getMessage eq null))
            t = e.getCause
        System.err.println(s"[\033[31m${opts.printedName}\033[0m] Unexpected error: ${t.getMessage}")
        System.exit(1)
    }
}
