/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.logging.rule

import java.io.OutputStream
import java.util.UUID

import com.lmax.disruptor.{EventFactory, EventProcessor, RingBuffer}

import org.midonet.logging.rule.Result
import org.midonet.midolman.logging.rule.DisruptorRuleLogEventChannel.RuleLogEvent
import org.midonet.midolman.rules.Rule
import org.midonet.midolman.simulation.Chain
import org.midonet.packets.IPAddr

trait RuleLogEventChannel {
     def handoff(loggerId: UUID, nwProto: Byte,
                 chain: Chain, rule: Rule,
                 srcIp: IPAddr, dstIp: IPAddr,
                 srcPort: Int, dstPort: Int,
                 result: Result): Long

    def start(): Unit
    def stop(): Unit

    def registerStream(loggerId: UUID, stream: OutputStream): Unit
    def unregisterStream(loggerId: UUID): Unit
}

object DisruptorRuleLogEventChannel {
    sealed class RuleLogEvent(var loggerId: UUID, var nwProto: Byte,
                              var chain: Chain, var rule: Rule,
                              var srcIp: IPAddr, var dstIp: IPAddr,
                              var srcPort: Int, var dstPort: Int,
                              var result: Result)

    object Factory extends EventFactory[RuleLogEvent] {
        override def newInstance(): RuleLogEvent =
            new RuleLogEvent(null, 0, null, null, null, null, 0, 0, null)
    }
}

class DisruptorRuleLogEventChannel(ringBuffer: RingBuffer[RuleLogEvent],
                                   processor: EventProcessor,
                                   eventHandler: RuleLogEventHandler)
    extends RuleLogEventChannel {

    override def stop(): Unit = {
        processor.halt()
    }

    override def registerStream(loggerId: UUID, stream: OutputStream): Unit = {
        eventHandler.registerStream(loggerId, stream)
    }

    override def unregisterStream(loggerId: UUID): Unit = {
        eventHandler.unregisterStream(loggerId)
    }

    override def start(): Unit = {
        ringBuffer.addGatingSequences(processor.getSequence)
        val t = new Thread(processor, "rule-event-logger")
        t.setDaemon(true)
        t.run()
    }

    override def handoff(loggerId: UUID, nwProto: Byte,
                         chain: Chain, rule: Rule,
                         srcIp: IPAddr, dstIp: IPAddr,
                         srcPort: Int, dstPort: Int,
                         result: Result): Long = {
        val seq = ringBuffer.next()
        val event = ringBuffer.get(seq)
        event.loggerId = loggerId
        event.nwProto = nwProto
        event.chain = chain
        event.rule = rule
        event.srcIp = srcIp
        event.dstIp = dstIp
        event.srcPort = srcPort
        event.dstPort = dstPort
        event.result = result
        seq
    }
}
