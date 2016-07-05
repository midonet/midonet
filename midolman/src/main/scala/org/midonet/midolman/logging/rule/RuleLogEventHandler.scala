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
import java.nio.ByteBuffer
import java.util.UUID

import scala.collection.mutable

import com.lmax.disruptor.{EventHandler, LifecycleAware}

import uk.co.real_logic.sbe.codec.java.DirectBuffer

import org.midonet.logging.rule.RuleLogEventBinarySerialization._
import org.midonet.logging.rule.{RuleLogEvent => RuleLogEventEncoder}
import org.midonet.midolman.logging.rule.DisruptorRuleLogEventChannel.RuleLogEvent
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr}

class RuleLogEventHandler extends EventHandler[RuleLogEvent]
                                  with LifecycleAware {

    private val eventEncoder = new RuleLogEventEncoder
    private val eventBuffer = new DirectBuffer(new Array[Byte](BufferSize))
    private val ipBuffer = ByteBuffer.allocate(16)

    private val uuidToStream = mutable.Map[UUID, OutputStream]()

    def registerStream(loggerId: UUID, stream: OutputStream): Unit = {
        uuidToStream(loggerId) = stream
    }

    def unregisterStream(loggerId: UUID): Unit = {
        uuidToStream.remove(loggerId)
    }

    override def onEvent(event: RuleLogEvent, sequence: Long,
                         endOfBatch: Boolean): Boolean = {
        eventEncoder.wrapForEncode(eventBuffer, 0)
            .srcPort(event.srcPort)
            .dstPort(event.dstPort)
            .nwProto(event.nwProto)
            .result(event.result)

        val chain = event.chain
        eventEncoder.chainId(0, chain.id.getMostSignificantBits)
        eventEncoder.chainId(1, chain.id.getLeastSignificantBits)
        eventEncoder.ruleId(0, event.rule.id.getMostSignificantBits)
        eventEncoder.ruleId(1, event.rule.id.getLeastSignificantBits)
        eventEncoder.time(System.currentTimeMillis)

        // Src/dst IP
        fillIpBuffer(event.srcIp)
        eventEncoder.putSrcIp(ipBuffer.array, 0, ipBuffer.position)
        fillIpBuffer(event.dstIp)
        eventEncoder.putDstIp(ipBuffer.array, 0, ipBuffer.position)

        eventEncoder.putMetadata(chain.metadata, 0, chain.metadata.length)

        val os = uuidToStream(event.loggerId)
        os.write(eventBuffer.array, 0, eventEncoder.limit)
    }

    private def fillIpBuffer(ip: IPAddr): Unit = {
        ipBuffer.position(0)
        ip match {
            case ip: IPv4Addr =>
                ipBuffer.putInt(ip.toInt)
            case ip: IPv6Addr =>
                ipBuffer.putLong(ip.upperWord).putLong(ip.lowerWord)
        }
    }

    override def onStart(): Unit = ???

    override def onShutdown(): Unit = ???
}
