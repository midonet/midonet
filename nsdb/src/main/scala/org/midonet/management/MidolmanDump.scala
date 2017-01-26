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

import java.io.IOException
import java.lang.management.{ManagementFactory, RuntimeMXBean}

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
                                 descr = "Output file")

        val dumpAll = opt[Boolean]("all", short = 'a', default = Some(DefaultDumpAllObjects),
                                   descr = "Include unreachable objects")

        printedName = "mm-dump"

        footer("Copyright (c) 2016 Midokura SARL, All Rights Reserved.")
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

    private def getRemoteCwd(conn: MBeanServerConnection): String = {
        val cwdProp = Array[String]("user.dir")
        try {
            val objName = new ObjectName(ManagementFactory.RUNTIME_MXBEAN_NAME)
            val runtime = JMX.newMBeanProxy(conn,
                                            objName,
                                            classOf[RuntimeMXBean],
                                            true)
            val props = runtime.getSystemProperties
                        .asInstanceOf[TabularDataSupport]

            props.get(cwdProp).asInstanceOf[CompositeData]
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
        val Slash = "/"
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

        val fullPath = if (destinationFile.startsWith(Slash)) {
            destinationFile
        } else {
            val cwd = getRemoteCwd(conn)
            val separator = if (cwd.endsWith(Slash)) "" else Slash
            s"$cwd$separator$destinationFile"
        }

        System.err.println(s"$INFO Dumping heap to $host:$fullPath ...")

        val onlyLiveObjects = ! opts.dumpAll.get.get
        bean.dumpHeap(destinationFile, onlyLiveObjects)

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
