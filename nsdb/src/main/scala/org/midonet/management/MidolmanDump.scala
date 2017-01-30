/*
 * Copyright 2017 Midokura SARL
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

import java.io.{BufferedWriter, File, FileWriter, IOException}
import java.lang.management.{ManagementFactory, RuntimeMXBean, ThreadMXBean}
import java.lang.management.ManagementFactory.THREAD_MXBEAN_NAME
import java.lang.management.ManagementFactory.getThreadMXBean
import java.lang.management.ManagementFactory.newPlatformMXBeanProxy
import java.nio.file.Paths

import javax.management.openmbean.{CompositeData, TabularDataSupport}
import javax.management.remote.{JMXConnectorFactory, JMXServiceURL}
import javax.management.{JMX, MBeanServerConnection, ObjectName}

import scala.util.control.NonFatal

import com.sun.management.HotSpotDiagnosticMXBean

import org.rogach.scallop._

object MidolmanDump extends App {

    System.setProperty("logback.configurationFile", "logback-disabled.xml")

    val DefaultHost = "localhost"
    val JMXDefaultPort = 7200
    val DefaultDumpFileName = "dump.hprof"
    val DefaultDumpAllObjects = false

    val opts = new ScallopConf(args) {
        val port = opt[Int]("port", short = 'p', default = Option(JMXDefaultPort),
                            descr = "JMX port",
                            required = true)
        val host = opt[String]("host", short = 'h', default = Option(DefaultHost),
                               descr = "Host")
        val output = opt[String]("output", short = 'o', default = None,
                                 descr = "Output file. Will be created on process" +
                                         " machine for heap dump and on current" +
                                         " machine for stack trace dump")

        val dumpAll = opt[Boolean]("all", short = 'a', default = Some(DefaultDumpAllObjects),
                                   descr = "Include unreachable objects")

        val dumpStackTrace = opt[Boolean]("stack-trace", short = 't',
            default = Some(false),
                descr = "Dump stack trace of all threads to given file")

        printedName = "mm-dump"

        footer("Copyright (c) 2017 Midokura SARL, All Rights Reserved.")
    }

    val ERROR = s"[\033[31m${opts.printedName}\033[0m]"
    val WARNING = s"[\033[33m${opts.printedName}\033[0m]"
    val INFO = s"[\033[32m${opts.printedName}\033[0m]"

    private def connect(host: String, port: Int): MBeanServerConnection = {
        val url = new JMXServiceURL(
            s"service:jmx:rmi:///jndi/rmi://$host:$port/jmxrmi")
        val jmxc = JMXConnectorFactory.connect(url, null)

        jmxc.getMBeanServerConnection
    }

    private def getVMWorkingDirectory(conn: MBeanServerConnection): String = {
        val userDir = Array[String]("user.dir")
        try {
            val objName = new ObjectName(ManagementFactory.RUNTIME_MXBEAN_NAME)
            val runtime = JMX.newMBeanProxy(conn,
                                            objName,
                                            classOf[RuntimeMXBean],
                                            true)
            val props = runtime.getSystemProperties
                        .asInstanceOf[TabularDataSupport]

            props.get(userDir).asInstanceOf[CompositeData]
                .get("value").asInstanceOf[String]

        } catch {
            case NonFatal(err) =>
                System.err.println("$WARNING Unable to get remote working directory.")
                "(unknown)"
        }
    }

    private def getExceptionMessage(e: Throwable): String = {
        var t = e
        while ((t.getCause ne null) && (t.getMessage eq null))
            t = e.getCause
        t.getMessage
    }

    try {
        val DiagnosticsBeanName = new ObjectName("com.sun.management:type=HotSpotDiagnostic")

        val host = opts.host.get.get
        val conn = connect(host, opts.port.get.get)
        val bean = JMX.newMBeanProxy(conn,
                                     DiagnosticsBeanName,
                                     classOf[HotSpotDiagnosticMXBean],
                                     true)

        val destinationFile = opts.output.get match {
            case Some(name) => name
            case None => DefaultDumpFileName
        }

        val destinationPath = Paths.get(destinationFile)

        val fullPath = if (destinationPath.isAbsolute) {
            destinationPath
        } else {
            Paths.get(getVMWorkingDirectory(conn)).resolve(destinationPath)
        }

        if (opts.dumpStackTrace.get.get) {
            val file = new File(destinationFile)
            if (file.exists()) {
                System.err.println(s"$ERROR output file $destinationFile" +
                " already exists")
            } else {
                System.err.println(s"$INFO Dumping stack trace to $destinationPath ...")
                val tmbean = newPlatformMXBeanProxy(conn,
                                                   THREAD_MXBEAN_NAME,
                                                   classOf[ThreadMXBean])

                val bw = new BufferedWriter(new FileWriter(file))
                bw.write(s"Total threads: " + tmbean.getThreadCount + "\n\n")
                tmbean.dumpAllThreads(tmbean.isObjectMonitorUsageSupported,
                    tmbean.isSynchronizerUsageSupported)
                    .map(_.toString) foreach (bw.write(_))
                bw.close()
            }
        } else {
            System.err.println(s"$INFO Dumping heap to $host:$fullPath ...")

            val onlyLiveObjects = ! opts.dumpAll.get.get
            bean.dumpHeap(destinationFile, onlyLiveObjects)
        }

    } catch {
        case e: IOException =>
            val msg = getExceptionMessage(e)
            System.err.println(s"$ERROR I/O error: $msg")
            System.exit(2)

        case e: Throwable =>
            e.printStackTrace()
            val msg = getExceptionMessage(e)
            System.err.println(s"$ERROR Unexpected error: $msg")
            System.exit(1)
    }
}
