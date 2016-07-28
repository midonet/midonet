/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.services.flowstate

import java.net.{DatagramPacket, DatagramSocket, InetAddress}
import java.nio.ByteBuffer
import java.util.{ArrayList, UUID}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

import akka.actor.{Actor, ActorSystem, Props}
import akka.contrib.throttle.Throttler._
import akka.contrib.throttle.TimerBasedThrottler

import com.codahale.metrics.{MetricRegistry, Timer}
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.minion.{Context, ExecutorsModule}
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.FlowStateStorePackets._
import org.midonet.packets.NatState.{NatBinding, NatKeyStore}
import org.midonet.packets.{IPv4Addr, NatState, SbeEncoder}
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.concurrent.NanoClock.{DEFAULT => clock}

import ch.qos.logback.classic.Level


case class WriteMessage(udp: DatagramPacket)
case object WriteReport

object FlowStateMinionBenchmark extends App {

    class Reporter extends Actor {
        def report(tag: String, metric: Timer) = {
            Log info s"TAG: $tag"
            Log info s"COUNT: ${metric.getCount}"
            Log info s"15: ${metric.getFifteenMinuteRate}"
            Log info s"5: ${metric.getFiveMinuteRate}"
            Log info s"1: ${metric.getOneMinuteRate}"
            Log info s"MEAN: ${metric.getMeanRate}\n"
        }

        def receive = {
            case WriteReport =>
                val w1 = metrics.getTimers().get("writeRate")
                val w2 = metrics.getTimers().get("clientRate")
                report("ServerRate", w1)
                report("ClientRate", w2)
            case "finished" =>
                Log info "FINAL REPORT:"
                val w1 = metrics.getTimers().get("writeRate")
                val w2 = metrics.getTimers().get("clientRate")
                report("ServerRate", w1)
                report("ClientRate", w2)
        }
    }

    class Writer extends Actor {
        def receive = {
            case WriteMessage(udp) =>
                val handler = clientRate.time()
                flowStateSocket.send(udp)
                handler.stop()
            case WriteReport =>

        }
    }

    class Reader extends Actor {
        def receive = {
            case x =>
        }
    }

    val usage =
        """
          |Usage: java -cp midolman-all.jar org.midonet.services.flowstate.FlowStateMinionBenchmark [--writerate rate] [--readrate rate] [--numports num] [--duration time_in_seconds]
        """.stripMargin

    if (args.length == 0) println(usage)
    type OptionMap = Map[Symbol, Int]

    def optionMap(map: OptionMap, list: List[String]) : OptionMap = {
        list match {
            case Nil => map
            case "--writerate" :: value :: tail =>
                optionMap(map ++ Map('writerate -> value.toInt), tail)
            case "--readrate" :: value :: tail =>
                optionMap(map ++ Map('readrate -> value.toInt), tail)
            case "--numports" :: value :: tail =>
                optionMap(map ++ Map('numports -> value.toInt), tail)
            case "--duration" :: value :: tail =>
                optionMap(map ++ Map('duration -> value.toInt), tail)
            case option :: tail =>
                println("Unknown option " + option)
                sys.exit(1)
        }
    }

    val defaults = Map('writerate -> 100,
                       'readrate -> 0,
                       'numports -> 1,
                       'duration -> 100)

    val options = optionMap(defaults, args.toList)
    println(options)

    System.setProperty("minions.db.dir",
                       s"${System.getProperty("java.io.tmpdir")}/")
    val TmpDirectory = s"benchmark-${NanoClock.DEFAULT.tick}"

    val Log = Logger(LoggerFactory.getLogger("FlowStateMinionBenchmark"))

    Log info s"Writing flow state to ${System.getProperty("minions.db.dir")}$TmpDirectory"
    private def logbackLogger(name: String) = LoggerFactory.getLogger(name).
        asInstanceOf[ch.qos.logback.classic.Logger]
    logbackLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO)

    val config = MidolmanConfig.forTests(s"""
           |agent.minions.flow_state.enabled : true
           |agent.minions.flow_state.legacy_push_state : false
           |agent.minions.flow_state.legacy_read_state : false
           |agent.minions.flow_state.local_push_state : true
           |agent.minions.flow_state.local_read_state : true
           |agent.minions.flow_state.log_directory: $TmpDirectory
           |cassandra.servers : "127.0.0.1:9142"
           |cassandra.cluster : "midonet"
           |cassandra.replication_factor : 1
           |agent.loggers.root : "INFO"
           """.stripMargin)

    val context = Context(UUID.randomUUID())

    val executor = ExecutorsModule(config.services.executors, Log)

    val metrics = new MetricRegistry()

    val clientRate = metrics.timer("clientRate")

    val minion = new FlowStateService(context, executor, config, metrics)

    val system = ActorSystem("FlowStateMinionBenchmark")

    val flowStateSocket = new DatagramSocket()

    Log debug "Registering shutdown hook"
    sys addShutdownHook {
        stop
    }

    Log info "Flow state standalone minion starting..."
    start

    private def validFlowStateInternalMessage(numConntracks: Int = 1,
                                              numNats: Int = 2,
                                              numTraces: Int = 0,
                                              ingress: UUID,
                                              egress: ArrayList[UUID],
                                              port: Short = 6688)
    : DatagramPacket = {
        // Buffers
        val flowStatePacket = new DatagramPacket(Array.emptyByteArray, 0,
                                                 InetAddress.getLoopbackAddress,
                                                 config.flowState.port)
        val flowStateBuffer = ByteBuffer.allocate(MaxMessageSize)
        val buffer = new Array[Byte](MaxMessageSize)

        val conntrackKeys = mutable.MutableList.empty[ConnTrackKeyStore]
        val natKeys = mutable.MutableList.empty[(NatKeyStore, NatBinding)]

        // Encode flow state message into buffer
        val encoder = new SbeEncoder()
        val flowStateMessage = encoder.encodeTo(buffer)

        // Encode sender
        val sender = UUID.randomUUID()
        uuidToSbe(sender, flowStateMessage.sender)

        // Encode keys
        val c = flowStateMessage.conntrackCount(numConntracks)
        while (c.hasNext) {
            val conntrackKey = randomConnTrackKey
            conntrackKeys += conntrackKey
            connTrackKeyToSbe(conntrackKey, c.next)
        }

        val n = flowStateMessage.natCount(numNats)
        while (n.hasNext) {
            val (natKey, natBinding) = (randomNatKey, randomNatBinding)
            natKeys += ((natKey, natBinding))
            natToSbe(natKey, natBinding, n.next)
        }

        val t = flowStateMessage.traceCount(0)
        val r = flowStateMessage.traceRequestIdsCount(0)

        // Encode ingress/egress ports
        val p = flowStateMessage.portIdsCount(1)
        portIdsToSbe(ingress, egress, p.next)

        flowStateBuffer.clear()
        flowStateBuffer.putInt(FlowStateInternalMessageType.FlowStateMessage)
        flowStateBuffer.putInt(encoder.encodedLength())
        flowStateBuffer.put(encoder.flowStateBuffer.array, 0, encoder.encodedLength())
        flowStateBuffer.flip()

        flowStatePacket.setData(flowStateBuffer.array(),
                                0,
                                FlowStateInternalMessageHeaderSize + flowStateBuffer.limit())

        flowStatePacket
    }

    private def validOwnedPortsMessage(portIds: List[UUID])
    : DatagramPacket = {
        val flowStatePacket = new DatagramPacket(Array.emptyByteArray, 0,
                                                 InetAddress.getLoopbackAddress,
                                                 config.flowState.port)
        val flowStateBuffer = ByteBuffer.allocate(MaxMessageSize)
        flowStateBuffer.putInt(FlowStateInternalMessageType.OwnedPortsUpdate)
        flowStateBuffer.putInt(portIds.size * 16) // UUID size = 16 bytes
        for (portId <- portIds) {
            flowStateBuffer.putLong(portId.getMostSignificantBits)
            flowStateBuffer.putLong(portId.getLeastSignificantBits)
        }
        flowStateBuffer.flip()

        flowStatePacket.setData(
            flowStateBuffer.array(),
            0,
            FlowStateInternalMessageHeaderSize + portIds.size * 16)
        flowStatePacket
    }

    private def randomConnTrackKey: ConnTrackKeyStore =
        ConnTrackKeyStore(IPv4Addr.random, randomPort,
                          IPv4Addr.random, randomPort,
                          0, UUID.randomUUID)

    private def randomNatKey: NatKeyStore =
        NatKeyStore(NatState.FWD_DNAT,
                    IPv4Addr.random, randomPort,
                    IPv4Addr.random, randomPort,
                    1, UUID.randomUUID)

    private def randomNatBinding: NatBinding =
        NatBinding(IPv4Addr.random, randomPort)

    private def randomPort: Int = Random.nextInt(Short.MaxValue + 1)

    private def start = {
        minion.startAsync().awaitRunning()
        runBenchmark
    }

    private def stop = {
        if (minion.isRunning) {
            Log info "Shutdown hook triggered, shutting down..."
            minion.stopAsync().awaitTerminated()
            sys.exit(0)
        }
    }

    private def runBenchmark = {
        val writeRate = options('writerate)
        val readRate = options('readrate)
        val numports = options('numports)
        val duration = options('duration)
        val writer = system.actorOf(Props(classOf[Writer]))
        val reader = system.actorOf(Props(classOf[Reader]))
        val reporter = system.actorOf(Props(classOf[Reporter]))
        val writeThrottler = system.actorOf(Props(classOf[TimerBasedThrottler],
                                                  writeRate msgsPer 1.second))
        val readThrottler = system.actorOf(Props(classOf[TimerBasedThrottler],
                                                 readRate msgsPer 1.second))
        val reporterThrottler = system.actorOf(Props(classOf[TimerBasedThrottler],
                                                 1 msgsPer 10.seconds))

        writeThrottler ! SetTarget(Some(writer))
        readThrottler ! SetTarget(Some(reader))
        reporterThrottler ! SetTarget(Some(reporter))

        val numWrites = duration * writeRate
        val numReads = duration * readRate

        val ingressPorts = Seq.fill(numports)(UUID.randomUUID).toList
        val egressPorts = Seq.fill(3)(UUID.randomUUID).toList.asJava

        val messages = mutable.MutableList.empty[DatagramPacket]
        for (i <- 1 to 100) {
            val ingressPort = ingressPorts(Random.nextInt(numports))
            messages += validFlowStateInternalMessage(
                ingress = ingressPort, egress = new ArrayList(egressPorts))
        }

        for (i <- 1 to (duration / 10)) {
            reporterThrottler ! WriteReport
        }
        reporterThrottler ! "finish"

        writeThrottler ! WriteMessage(validOwnedPortsMessage(ingressPorts))

        for (i <- 0 until numWrites) {
            writeThrottler ! messages(Random.nextInt(messages.length))
        }

        for (i <- 0 until numReads) {
            readThrottler ! s"$i"
        }
    }
}
