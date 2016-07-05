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

import java.util.UUID

import com.lmax.disruptor.{BatchEventProcessor, EventFactory, EventProcessor, RingBuffer}

import org.midonet.logging.rule.Result
import org.midonet.midolman.logging.MidolmanLogging
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
    def isStarted: Boolean
    def stop(): Unit

    /**
      * Test method that blocks until all events are processed and then flushes
      * stream. Not suitable for production use.
      */
    def flush(): Unit
}

object DisruptorRuleLogEventChannel {
    sealed class RuleLogEvent(var loggerId: UUID, var nwProto: Byte,
                              var chain: Chain, var rule: Rule,
                              var srcIp: IPAddr, var dstIp: IPAddr,
                              var srcPort: Int, var dstPort: Int,
                              var result: Result) {

        override def toString: String = {
            s"RuleLogEvent(loggerId=$loggerId, nwProto=$nwProto, " +
            s"chainId=${chain.id}, ruleId=${rule.id} srcIp=$srcIp, " +
            s"dstIp=$dstIp, srcPort=$srcPort, dstPort=$dstPort, " +
            s"result=$result)"
        }
    }

    object Factory extends EventFactory[RuleLogEvent] {
        override def newInstance(): RuleLogEvent =
            new RuleLogEvent(null, 0, null, null, null, null, 0, 0, null)
    }

    def apply(capacity: Int, logPath: String = null)
    : DisruptorRuleLogEventChannel = {
        val ringBuffer = RingBuffer
            .createMultiProducer(Factory, capacity)
        val barrier = ringBuffer.newBarrier()
        val eventHandler = if (logPath == null) {
            new FileRuleLogEventHandler()
        } else {
            new FileRuleLogEventHandler(logPath)
        }
        val batchProcessor =
            new BatchEventProcessor(ringBuffer, barrier, eventHandler)
        new DisruptorRuleLogEventChannel(ringBuffer, batchProcessor,
                                         eventHandler)
    }
}

class DisruptorRuleLogEventChannel(ringBuffer: RingBuffer[RuleLogEvent],
                                   processor: EventProcessor,
                                   eventHandler: RuleLogEventHandler)
    extends RuleLogEventChannel with MidolmanLogging {

    private var started: Boolean = false

    override def stop(): Unit = {
        processor.halt()
        started = false
    }

    override def start(): Unit = {
        log.debug("Starting DisruptorRuleLogEventChannel")
        ringBuffer.addGatingSequences(processor.getSequence)
        val t = new Thread(processor, "rule-event-logger")
        t.setDaemon(true)
        t.start()
        started = true
    }


    override def isStarted: Boolean = started

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
        ringBuffer.publish(seq)
        seq
    }

    override def flush(): Unit = {
        while (ringBuffer.remainingCapacity() < ringBuffer.getBufferSize) {
            Thread.sleep(50)
        }
        eventHandler.flush()
    }
}
