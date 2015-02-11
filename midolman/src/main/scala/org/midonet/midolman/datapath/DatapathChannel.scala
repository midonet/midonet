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

import com.lmax.disruptor._
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.datapath.DisruptorDatapathChannel.DatapathEvent
import org.midonet.netlink._
import org.midonet.odp._
import org.midonet.odp.flows.FlowAction
import org.slf4j.LoggerFactory

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
            new DatapathEvent(BytesUtil.instance.allocateDirect(8*1024), -1)
    }
}

class DisruptorDatapathChannel(ovsFamilies: OvsNetlinkFamilies,
                               ringBuffer: RingBuffer[DatapathEvent],
                               processors: Array[_ <: EventProcessor]) extends DatapathChannel {
    import DisruptorDatapathChannel._

    private var datapathId: Int = _
    private var supportsMegaflow: Boolean = _
    private val protocol = new OvsProtocol(0, ovsFamilies)

    def start(datapath: Datapath): Unit = {
        datapathId = datapath.getIndex
        supportsMegaflow = datapath.supportsMegaflow()

        processors foreach { proc =>
            ringBuffer.addGatingSequences(proc.getSequence)
        }

        processors.zipWithIndex foreach { case (proc, idx) =>
            val t = new Thread("datapath-output-" + idx) {
                override def run() {
                    proc.run()
                }
            }
            t.setDaemon(true)
            t.start()
        }
    }

    def stop(): Unit =
        processors foreach (_.halt())

    def executePacket(packet: Packet,
                      actions: JList[FlowAction]): Unit = {
        if (actions.isEmpty) {
            return
        }

        val seq = ringBuffer.next()
        val event = ringBuffer.get(seq)
        event.bb.clear()

        protocol.preparePacketExecute(datapathId, packet, actions, event.bb)
        event.op = PACKET_EXECUTION
        ringBuffer.publish(seq)
    }

    def createFlow(flow: Flow): Unit = {
        val seq = ringBuffer.next()
        flow.getMatch.setSequence(seq)

        val event = ringBuffer.get(seq)
        event.bb.clear()

        protocol.prepareFlowCreate(datapathId, supportsMegaflow, flow, event.bb)
        event.op = FLOW_CREATE
        ringBuffer.publish(seq)
    }
}
