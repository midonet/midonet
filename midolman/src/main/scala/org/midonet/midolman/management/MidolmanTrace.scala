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
import java.util
import org.rogach.scallop._
import javax.management.remote.{JMXConnectorFactory, JMXServiceURL}
import javax.management.{JMX, ObjectName}
import scala.util.{Failure, Success, Try}

object TraceCommand {
    val SUCCESS = 0
    val FAILURE = 1
}

trait TraceCommand {
    def run(tracingProxy: PacketTracingMXBean): Int
}

abstract class Matcher(name: String) extends Subcommand(name) {
    implicit def scallopShortToBoxed(opt: ScallopOption[Short]): JShort =
        opt.get.map( new JShort(_) ).orNull

    implicit def scallopByteToBoxed(opt: ScallopOption[Byte]): JByte =
        opt.get.map( new JByte(_) ).orNull

    implicit def scallopIntToBoxed(opt: ScallopOption[Int]): JInt =
        opt.get.map( new JInt(_) ).orNull

    implicit def scallopStringToNaked(opt: ScallopOption[String]): String =
        opt.get.orNull

    implicit def scallopIntToNaked(opt: ScallopOption[Int]): Int =
        opt.get.orElse(Some(1)).get

    val debug = opt[Boolean]("debug", short = 'd',
            descr = "logs at debug level")
    val trace = opt[Boolean]("trace", short = 't',
            descr = "logs at trace level")
    val etherType = opt[Short]("ethertype", noshort = true,
            descr = "match on ethertype")
    val macSrc = opt[String]("mac-src", noshort = true,
            descr = "match on source MAC address")
    val macDst = opt[String]("mac-dst", noshort = true,
            descr = "match on destination MAC address")
    val ipProto = opt[Byte]("ip-protocol", noshort = true,
            descr = "match on ip protocol field")
    val ipSrc = opt[String]("ip-src", noshort = true,
            descr = "match on ip source address")
    val ipDst = opt[String]("ip-dst", noshort = true,
            descr = "match on ip destination address")
    val srcPort = opt[Int]("src-port", noshort = true,
            descr = "match on TCP/UDP source port")
    val dstPort = opt[Int]("dst-port", noshort = true,
            descr = "match on TCP/UDP destination port")
    val limit = opt[Int]("limit", short = 'l',
            descr = "number of packets to match before disabling this trace")
    requireOne(debug, trace)
    mutuallyExclusive(debug, trace)

    def makeTracer: PacketTracer =
        PacketTracer(etherType, macSrc, macDst, ipProto,
                     ipSrc, ipDst, srcPort, dstPort,
                     if (debug.get.isDefined && debug.get.get) LogLevel.DEBUG else LogLevel.TRACE,
                     limit)
}

object AddTrace extends Matcher("add") with TraceCommand {
    descr("add a packet tracing match")

    override def run(tracingProxy: PacketTracingMXBean) = {
        tracingProxy.addTracer(makeTracer)
        TraceCommand.SUCCESS
    }
}

object RemoveTrace extends Matcher("remove") with TraceCommand {
    descr("remove a packet tracing match")

    override def run(tracingProxy: PacketTracingMXBean) = {
        val num = tracingProxy.removeTracer(makeTracer)
        System.out.println(s"Removed $num tracer(s)")
        TraceCommand.SUCCESS
    }
}

object FlushTraces extends Subcommand("flush") with TraceCommand {
    descr("clear the list of tracing matches")

    val deadOnly = opt[Boolean]("dead-only", short = 'D',
        descr = "flush expired tracers only")

    override def run(tracingProxy: PacketTracingMXBean) = {
        val num = if (deadOnly.get.isDefined && deadOnly.get.get)
                      tracingProxy.flushDeadTracers()
                  else
                      tracingProxy.flush()
        System.out.println(s"Removed $num tracer(s)")
        TraceCommand.SUCCESS
    }
}

object ListTraces extends Subcommand("list") with TraceCommand {
    descr("list all active tracing matches")

    val liveOnly = opt[Boolean]("live-only", short = 'L',
        descr = "list active tracers only")

    override def run(tracingProxy: PacketTracingMXBean) = {
        val tracers = if (liveOnly.get.isDefined && liveOnly.get.get)
                          tracingProxy.getLiveTracers
                      else
                          tracingProxy.getTracers

        for (l <- tracers) {
            System.out.println(l)
        }
        TraceCommand.SUCCESS
    }
}

object MidolmanTrace extends App {
    private def getTracingBean(host: String, port: Int): Try[PacketTracingMXBean] = {
        try {
            val url = new JMXServiceURL(
                s"service:jmx:rmi:///jndi/rmi://$host:$port/jmxrmi")
            val jmxc = JMXConnectorFactory.connect(url, null)

            val mbsc = jmxc.getMBeanServerConnection

            val name = new ObjectName(PacketTracingMXBean.NAME)
            Success(JMX.newMXBeanProxy(mbsc, name, classOf[PacketTracingMXBean], true))
        } catch {
            case e: Exception =>
                Failure(new Exception(
                    "[mm-trace] Failed to connect to remote agent: " + e.getMessage))
        }
    }

    val opts = new ScallopConf(args) {
        val port = opt[Int]("port", short = 'p', default = Option(7200),
                            descr = "JMX port",
                            required = true)
        val host = opt[String]("host", short = 'h', default = Option("localhost"),
                               descr = "Host")

        val add = AddTrace
        val remove = RemoveTrace
        val flush = FlushTraces
        val list = ListTraces

        printedName = "mm-trace"
        footer("Copyright (c) 2014 Midokura SARL, All Rights Reserved.")
    }

    val ret = (opts.subcommand flatMap {
        case subcommand: TraceCommand =>
            for {host <- opts.host.get
                 port <- opts.port.get} yield { (subcommand, host, port) }
        case _ =>
            None
    } match {
        case Some((subcommand, host, port)) =>
            getTracingBean(host, port) map { bean => subcommand.run(bean) }
        case _ =>
            Failure(new Exception("[mm-trace] must specify a valid command"))
    }) match {
        case Success(retcode) =>
            retcode
        case Failure(e) =>
            System.err.println("[mm-trace] Failed: " + e.getMessage)
            1
    }

    System.exit(ret)
}
