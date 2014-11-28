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

import java.lang.{Short => JShort, Integer => JInt, Byte => JByte}
import org.midonet.odp.flows.FlowStats
import org.rogach.scallop._
import javax.management.remote.{JMXConnectorFactory, JMXServiceURL}
import javax.management.{JMX, ObjectName}
import scala.util.{Failure, Success, Try}

object MeterCommand {
    val SUCCESS = 0
    val FAILURE = 1
}

trait MeterCommand {
    def run(meteringProxy: MeteringMXBean): Int
}

object GetMeter extends Subcommand("get") with MeterCommand {
    implicit def scallopStringToNaked(opt: ScallopOption[String]): String =
        opt.get.orNull
    implicit def scallopIntToNaked(opt: ScallopOption[Int]): Int =
        opt.get.orElse(Some(1)).get

    val name = opt[String]("meter-name", short = 'n', required = true,
                           descr = "name of the meter")
    val delay = trailArg[Int](required = false, default = Some(0),
                              descr = "delay between updates, in seconds. If no "+
                                "delay is specified, only one report is printed.")
    val count = trailArg[Int](required = false, default = Some(Integer.MAX_VALUE),
                              descr = "number of updates, defaults to infinity")

    override def run(meteringProxy: MeteringMXBean): Int = {
        val meterName: String = name
        val delaySecs: Int = delay
        var iterations: Int = if (delaySecs > 0) count else 1

        val packetsHeader = "packets"
        val bytesHeader = "bytes"
        val header = f"$packetsHeader%12s $bytesHeader%12s"
        System.out.println(header)

        var lastStats = new FlowStats()
        do {
            val newStats = meteringProxy.getMeter(meterName)
            val packetDelta = newStats.packets - lastStats.packets
            val byteDelta = newStats.bytes - lastStats.bytes
            lastStats = newStats

            System.out.println(f"$packetDelta%12s $byteDelta%12s")

            if (delaySecs > 0)
                Thread.sleep(delaySecs * 1000)
            iterations -= 1
        } while (iterations > 0)

        MeterCommand.SUCCESS
    }
}

object ListMeters extends Subcommand("list") with MeterCommand {
    descr("list all active meters")

    override def run(meteringProxy: MeteringMXBean): Int = {
        val meters = meteringProxy.listMeters

        for (l <- meters)
            System.out.println(l)
        MeterCommand.SUCCESS
    }
}

object MidolmanMeter extends App {
    private def getMeteringBean(host: String, port: Int): Try[MeteringMXBean] = {
        try {
            val url = new JMXServiceURL(
                s"service:jmx:rmi:///jndi/rmi://$host:$port/jmxrmi")
            val jmxc = JMXConnectorFactory.connect(url, null)

            val mbsc = jmxc.getMBeanServerConnection

            val name = new ObjectName(MeteringMXBean.NAME)
            Success(JMX.newMXBeanProxy(mbsc, name, classOf[MeteringMXBean], true))
        } catch {
            case e: Exception =>
                Failure(new Exception(
                    "[mm-meter] Failed to connect to remote agent: " + e.getMessage))
        }
    }

    val opts = new ScallopConf(args) {
        val port = opt[Int]("port", short = 'p', default = Option(7200),
                            descr = "JMX port",
                            required = true)
        val host = opt[String]("host", short = 'h', default = Option("localhost"),
                               descr = "Host")

        val list = ListMeters
        val get = GetMeter

        printedName = "mm-meter"
        footer("Copyright (c) 2014 Midokura SARL, All Rights Reserved.")
    }

    val ret = (opts.subcommand flatMap {
        case subcommand: MeterCommand =>
            for {host <- opts.host.get
                 port <- opts.port.get} yield { (subcommand, host, port) }
        case _ =>
            None
    } match {
        case Some((subcommand, host, port)) =>
            getMeteringBean(host, port) map { bean => subcommand.run(bean) }
        case _ =>
            Failure(new Exception("[mm-meter] must specify a valid command"))
    }) match {
        case Success(retcode) =>
            retcode
        case Failure(e) =>
            System.err.println("[mm-meter] Failed: " + e.getMessage)
            1
    }

    System.exit(ret)
}
