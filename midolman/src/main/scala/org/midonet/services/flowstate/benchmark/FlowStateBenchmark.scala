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

package org.midonet.services.flowstate.benchmark

import java.net.{DatagramPacket, DatagramSocket, InetAddress}
import java.nio.ByteBuffer
import java.util.concurrent.{Executors, TimeUnit}
import java.util.{ArrayList, UUID}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random

import akka.actor.ActorSystem

import com.codahale.metrics.{Meter, MetricRegistry}
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.FlowStateStorePackets._
import org.midonet.packets.NatState.{NatBinding, NatKeyStore}
import org.midonet.packets.{IPv4Addr, NatState, SbeEncoder}
import org.midonet.services.flowstate.{FlowStateInternalMessageType, _}
import org.midonet.util.concurrent.NanoClock.{DEFAULT => clock}

import ch.qos.logback.classic.Level

object FlowStateBenchmark extends App {

    val usage =
        """
          |Usage: java -cp midolman.jar org.midonet.services.flowstate.benchmark.FlowStateBenchmark [--writerate rate] [--readrate rate] [--numports num] [--messagesPerPort num (0 for always random)]
        """.stripMargin

    if (args.length == 0) println(usage)
    type OptionMap = Map[Symbol, Long]

    def optionMap(map: OptionMap, list: List[String]) : OptionMap = {
        list match {
            case Nil => map
            case "--writerate" :: value :: tail =>
                optionMap(map ++ Map('writeRate -> value.toLong), tail)
            case "--readrate" :: value :: tail =>
                optionMap(map ++ Map('readRate -> value.toLong), tail)
            case "--numports" :: value :: tail =>
                optionMap(map ++ Map('numPorts -> value.toLong), tail)
            case "--duration" :: value :: tail =>
                optionMap(map ++ Map('duration -> value.toLong), tail)
            case "--messagesPerPort" :: value :: tail =>
                optionMap(map ++ Map('messagesPerPort -> value.toLong), tail)
            case "--numProcs" :: value :: tail =>
                optionMap(map ++ Map('numProcs -> value.toLong), tail)
            case "--overhead" :: value :: tail =>
                optionMap(map ++ Map('overhead -> value.toLong), tail)
            case option :: tail =>
                println("Unknown option " + option)
                sys.exit(1)
        }
    }

    val defaults = Map('writeRate -> 50000L,
                       'readRate -> 0L,
                       'numPorts -> 1L,
                       'duration -> 300L,
                       'messagesPerPort -> 10000L,
                       'numProcs -> Runtime.getRuntime.availableProcessors().toLong,
                       'overhead -> 1000L)

    val options = optionMap(defaults, args.toList)
    println(options)

    val Log = Logger(LoggerFactory.getLogger("FlowStateMinionBenchmark"))

    private def logbackLogger(name: String) = LoggerFactory.getLogger(name).
        asInstanceOf[ch.qos.logback.classic.Logger]
    logbackLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO)

    val metrics = new MetricRegistry()

    val metric = metrics.timer("clientRate")

    @volatile var counts: Meter = null
    val system = ActorSystem("FlowStateMinionBenchmark")

    val flowStateSocket = new DatagramSocket()

    val buffer = new Array[Byte](MaxMessageSize)

    Log debug "Registering shutdown hook"
    sys addShutdownHook {
        stop
    }

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
                                                 port)
        val flowStateBuffer = ByteBuffer.allocate(500)

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

    private def validOwnedPortsMessage(portIds: Seq[UUID])
    : DatagramPacket = {
        val flowStatePacket = new DatagramPacket(Array.emptyByteArray, 0,
                                                 InetAddress.getLoopbackAddress,
                                                 6688)
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



    val writeRate = options('writeRate)
    val readRate = options('readRate)
    val numports = options('numPorts).toInt
    val duration = options('duration).toInt
    val messagesPerPort = options('messagesPerPort).toInt
    val numProcs = options('numProcs).toInt
    val overhead = options('overhead).toInt

    val ingressPorts = Seq.fill(numports)(UUID.randomUUID).toArray
    val egressPorts = Seq.fill(3)(UUID.randomUUID).toList.asJava
    val egressPortsList = new ArrayList(egressPorts)

    val messagesMap = mutable.HashMap.empty[UUID, Seq[DatagramPacket]]
    for (i <- 0 until numports) {
        val ingressPort = ingressPorts(i)
        val messages = mutable.ArraySeq.fill(messagesPerPort) (
            validFlowStateInternalMessage(
                ingress = ingressPort, egress = egressPortsList))
        messagesMap += ingressPort -> messages
    }

    val pools = Seq.fill(numProcs)(Executors.newScheduledThreadPool(1))
    val poolReporter = Executors.newScheduledThreadPool(1)

    private def stop = {
        Log info "Shutdown hook triggered, shutting down..."
        for (pool <- pools) {
            pool.shutdown()
        }
        sys.exit(0)
    }

    Log info "Starting minion benchmark..."
    start

    private def start = {
        val period = (Math.pow(10, 9).toLong / writeRate) * pools.length
        val initperiod = period / pools.length
        Log info s"PERIOD: $period"

        val report = new Runnable {
            override def run = {
                if (counts != null) {
                    Log info s"\tclient " +
                             s"${clock.tick} " +
                             f"counts ${counts.getCount}\t" +
                             f"rate ${counts.getOneMinuteRate}%.2f"
                }
            }
        }

        val write = new Runnable {

            override def run = {
                val port = ingressPorts(Random.nextInt(ingressPorts.length))
                counts.mark()
                var message: DatagramPacket = null
                if (messagesPerPort > 0) {
                    message = messagesMap.get(port)
                        .get(Random.nextInt(messagesPerPort))
                } else {
                    message = validFlowStateInternalMessage(ingress = port,
                                                            egress = egressPortsList)
                }
                flowStateSocket.send(message)
            }
        }

        Log info s"Num processes: ${Runtime.getRuntime.availableProcessors()}"
        val owned = validOwnedPortsMessage(ingressPorts)
        flowStateSocket.send(owned)
        counts = metrics.meter("writeMeter")
        for (i <- 0 until pools.length) {
            pools(i).scheduleAtFixedRate(write,
                                         initperiod * i,
                                         period,
                                         TimeUnit.NANOSECONDS)
        }
        poolReporter.scheduleAtFixedRate(report, 10, 1, TimeUnit.SECONDS)
    }
}
