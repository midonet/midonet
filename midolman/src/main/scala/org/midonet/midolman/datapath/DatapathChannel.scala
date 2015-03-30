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

import com.lmax.disruptor._
import org.midonet.midolman.datapath.DisruptorDatapathChannel.PacketContextHolder
import org.midonet.midolman.simulation.PacketContext

trait DatapathChannel {
    def handoff(context: PacketContext): Long

    def start(): Unit
    def stop(): Unit
}

object DisruptorDatapathChannel {
    sealed class PacketContextHolder(var packetExecRef: PacketContext,
                                     var flowCreateRef: PacketContext)

    object Factory extends EventFactory[PacketContextHolder] {
        override def newInstance() = new PacketContextHolder(null, null)
    }
}

class DisruptorDatapathChannel(ringBuffer: RingBuffer[PacketContextHolder],
                               processors: Array[_ <: EventProcessor]) extends DatapathChannel {
    def start(): Unit = {
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

    def handoff(context: PacketContext): Long = {
        val seq = ringBuffer.next()
        val event = ringBuffer.get(seq)
        event.packetExecRef = context
        event.flowCreateRef = context
        ringBuffer.publish(seq)
        seq
    }
}
