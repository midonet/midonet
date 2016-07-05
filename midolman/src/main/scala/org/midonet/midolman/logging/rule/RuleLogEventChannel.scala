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

package org.midonet.midolman.logging.rule

import java.util.UUID

import com.google.common.util.concurrent.AbstractService
import com.lmax.disruptor._

import org.midonet.logging.rule.Result
import org.midonet.midolman.config.RuleLoggingConfig
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.logging.rule.DisruptorRuleLogEventChannel.RuleLogEvent
import org.midonet.midolman.rules.Rule
import org.midonet.midolman.simulation.Chain
import org.midonet.packets.IPAddr

abstract class RuleLogEventChannel extends AbstractService {
     def handoff(loggerId: UUID, nwProto: Byte,
                 chain: Chain, rule: Rule,
                 srcIp: IPAddr, dstIp: IPAddr,
                 srcPort: Int, dstPort: Int,
                 result: Result): Long

    /**
      * Blocks until all events are processed and then flushes stream.
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

    def apply(capacity: Int, config: RuleLoggingConfig)
    : DisruptorRuleLogEventChannel = {
        val ringBuffer = RingBuffer
            .createMultiProducer(Factory, capacity)
        val barrier = ringBuffer.newBarrier()

        val eventHandler = if (config.logDirectory == "") {
            new FileRuleLogEventHandler(config)
        } else {
            new FileRuleLogEventHandler(config, config.logDirectory)
        }

        val batchProcessor =
            new BatchEventProcessor(ringBuffer, barrier, eventHandler)
        batchProcessor.setExceptionHandler(eventHandler)
        ringBuffer.addGatingSequences(batchProcessor.getSequence)

        new DisruptorRuleLogEventChannel(ringBuffer, batchProcessor,
                                         eventHandler)
    }
}

class DisruptorRuleLogEventChannel(
        private val ringBuffer: RingBuffer[RuleLogEvent],
        private val processor: EventProcessor,
        private val eventHandler: RuleLogEventHandler)
    extends RuleLogEventChannel with MidolmanLogging {

    override def doStart(): Unit = {
        log.debug("Starting DisruptorRuleLogEventChannel")
        val t = new Thread(processor, "rule-event-logger")
        t.setDaemon(true)
        t.start()
        notifyStarted()
    }

    override def doStop(): Unit = {
        flush()
        processor.halt()
        notifyStopped()
    }

    override def handoff(loggerId: UUID, nwProto: Byte,
                         chain: Chain, rule: Rule,
                         srcIp: IPAddr, dstIp: IPAddr,
                         srcPort: Int, dstPort: Int,
                         result: Result): Long = {
        val seq = try ringBuffer.tryNext() catch {
            case ex: InsufficientCapacityException =>
                // TODO: Increment metric
                log.debug("Dropping rule log event due to insufficient " +
                          "buffer capacity.")
                return -1
        }
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
