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
package org.midonet.midolman.datapath

import java.nio.ByteBuffer
import java.util.{List => JList}
import scala.collection.immutable.IndexedSeq

import com.lmax.disruptor._

import com.typesafe.scalalogging.Logger
import org.midonet.midolman.flows.FlowEjector
import org.slf4j.LoggerFactory

import org.midonet.netlink._
import org.midonet.odp._
import org.midonet.odp.flows.FlowAction
import org.midonet.util.concurrent.{NanoClock, BackchannelEventProcessor}

trait DatapathChannel {
    def executePacket(packet: Packet, actions: JList[FlowAction]): Unit
    def createFlow(flow: Flow): Unit

    def start(datapath: Datapath): Unit
    def stop(): Unit
}

object DisruptorDatapathChannel {
    val PACKET_EXECUTION: Byte = 0
    val FLOW_CREATE: Byte = 1

    sealed class DatapathEvent(var bb: ByteBuffer, var op: Byte)

    object Factory extends EventFactory[DatapathEvent] {
        override def newInstance(): DatapathEvent =
            new DatapathEvent(ByteBuffer.allocateDirect(8*1024), 0)
    }
}

class DisruptorDatapathChannel(capacity: Int,
                               threads: Int,
                               flowEjector: FlowEjector,
                               channelFactory: NetlinkChannelFactory,
                               ovsFamilies: OvsNetlinkFamilies,
                               clock: NanoClock) extends DatapathChannel {
    import org.midonet.midolman.datapath.DisruptorDatapathChannel._

    private val log = Logger(LoggerFactory.getLogger(
        s"org.midonet.datapath.flow-creator"))

    private val ringBuffer = RingBuffer.createMultiProducer[DatapathEvent](Factory, capacity)
    private val barrier = ringBuffer.newBarrier()

    private var processors: IndexedSeq[EventProcessor] = _
    private var datapathId: Int = _
    private val protocol = new OvsProtocol(0, ovsFamilies)

    def start(datapath: Datapath): Unit = {
        this.datapathId = datapath.getIndex
        processors.zipWithIndex foreach { case (proc, idx) =>
            val t = new Thread("datapath-output-" + idx) {
                override def run() {
                    proc.run()
                }
            }
            t.setDaemon(true)
            t.start()
        }
        createProcessors()
    }

    private def createProcessors(): Unit = {
        val numPacketHandlers = Math.max(1, threads - 1)
        val flowHandler = new FlowProcessor(flowEjector, channelFactory,
                                            datapathId, ovsFamilies, clock)
        processors = (0 until numPacketHandlers).map { id =>
            val pexec = new PacketExecutor(numPacketHandlers, id, channelFactory)
            new BatchEventProcessor(ringBuffer, barrier, pexec)
        } :+ new BackchannelEventProcessor(ringBuffer, flowHandler, flowHandler)
    }

    def stop(): Unit =
        processors foreach (_.halt())

    def executePacket(packet: Packet,
                      actions: JList[FlowAction]): Unit = {
        if (actions.isEmpty) {
            return
        }

        val seq = ringBuffer.getCursor
        val event = ringBuffer.get(seq)
        event.bb.clear()

        val ack = log.underlying.isDebugEnabled
        protocol.preparePacketExecute(datapathId, packet, actions, ack, event.bb)
        event.op = PACKET_EXECUTION
        ringBuffer.publish(seq)
    }

    override def createFlow(flow: Flow): Unit = {
        val seq = ringBuffer.getCursor
        flow.getMatch.setSequence(seq)

        val event = ringBuffer.get(seq)
        event.bb.clear()

        val echo = log.underlying.isDebugEnabled
        protocol.prepareFlowCreate(datapathId, flow, echo, event.bb)
        event.op = FLOW_CREATE
        ringBuffer.publish(seq)
    }
}
