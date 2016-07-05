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

package org.midonet.midolman.simulation

import java.io.{BufferedOutputStream, FileOutputStream}
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.Charset
import java.util.UUID

import scala.util.control.NonFatal

import uk.co.real_logic.sbe.codec.java.DirectBuffer

import org.midonet.logging.rule.RuleLogEventBinarySerialization.{BufferSize, MessageTemplateVersion}
import org.midonet.logging.rule.{MessageHeader, Result, RuleLogEvent}
import org.midonet.midolman.rules.Rule
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag

private object RuleLogger {
    lazy val Utf8 = Charset.forName("UTF-8")
}

trait RuleLogger extends VirtualDevice {
    import RuleLogger._

    val id: UUID
    val logAcceptEvents: Boolean
    val logDropEvents: Boolean

    protected val HeaderEncoder = new MessageHeader
    protected val EventEncoder = new RuleLogEvent

    private val eventBuffer = new DirectBuffer(new Array[Byte](BufferSize))
    private val ipBuffer = ByteBuffer.allocate(16)
    private val metadataBuffer = ByteBuffer.allocate(8192)

    private val Utf8Enc = Utf8.newEncoder()

    override def deviceTag: FlowTag = FlowTagger.tagForRuleLogger(id)

    def logAccept(pktCtx: PacketContext, chain: Chain, rule: Rule): Unit = {
        if (logAcceptEvents)
            logEvent(pktCtx, Result.ACCEPT, chain, rule)
    }

    def logDrop(pktCtx: PacketContext, chain: Chain, rule: Rule): Unit = {
        if (logDropEvents)
            logEvent(pktCtx, Result.DROP, chain, rule)
    }

    private def logEvent(pktCtx: PacketContext, result: Result,
                         chain: Chain, rule: Rule): Unit = {
        val m = pktCtx.wcmatch
        HeaderEncoder.wrap(eventBuffer, 0, MessageTemplateVersion)
            .blockLength(EventEncoder.sbeBlockLength())
            .templateId(EventEncoder.sbeTemplateId())
            .schemaId(EventEncoder.sbeSchemaId())
            .version(EventEncoder.sbeSchemaVersion())

        // TODO: Time
        EventEncoder.wrapForEncode(eventBuffer, HeaderEncoder.size)
            .srcPort(m.getSrcPort)
            .dstPort(m.getDstPort)
            .nwProto(m.getNetworkProto)
            .result(result)

        EventEncoder.chainId(0, chain.id.getMostSignificantBits)
        EventEncoder.chainId(1, chain.id.getLeastSignificantBits)
        EventEncoder.ruleId(0, rule.id.getMostSignificantBits)
        EventEncoder.ruleId(1, rule.id.getLeastSignificantBits)

        // Src/dst IP
        fillIpBuffer(m.getNetworkSrcIP)
        EventEncoder.putSrcIp(ipBuffer.array, 0, ipBuffer.arrayOffset)
        fillIpBuffer(m.getNetworkDstIP)
        EventEncoder.putDstIp(ipBuffer.array, 0, ipBuffer.arrayOffset)

        // Metadata
        // TODO: Bounds checking
        metadataBuffer.reset()
        var i = 0
        while (i < chain.metadata.size) {
            val entry = chain.metadata(i)
            writeMetadataString(entry._1, lastStr = false)
            writeMetadataString(entry._2, i == chain.metadata.size)
        }
        EventEncoder.putMetadata(metadataBuffer.array, 0,
                                 metadataBuffer.position)

        logEvent(eventBuffer.array(), EventEncoder.limit)
    }

    private def writeMetadataString(cb: CharBuffer, lastStr: Boolean): Unit = {
        val lenPos = metadataBuffer.position
        Utf8Enc.encode(cb, metadataBuffer, lastStr)
        val len = (metadataBuffer.position - lenPos - 2).toShort
        metadataBuffer.putShort(lenPos, len)
    }

    private def fillIpBuffer(ip: IPAddr): Unit = {
        ipBuffer.reset()
        ip match {
            case ip: IPv4Addr =>
                ipBuffer.putInt(ip.toInt)
            case ip: IPv6Addr =>
                ipBuffer.putLong(ip.upperWord).putLong(ip.lowerWord)
        }
    }

    protected def logEvent(bytes: Array[Byte], len: Int): Unit
}

object FileRuleLogger {
    lazy val DefaultLogDir = try {
        System.getProperty("midolman.log.dir", "/var/log/midolman")
    } catch {
        case NonFatal(ex) => "/var/log/midolman"
    }
}

case class FileRuleLogger(id: UUID,
                          fileName: String,
                          logAcceptEvents: Boolean,
                          logDropEvents: Boolean,
                          logDir: String = FileRuleLogger.DefaultLogDir)
                         (oldLogger: FileRuleLogger = null)
    extends RuleLogger {

    private val os =
        new BufferedOutputStream(new FileOutputStream(s"$logDir/$fileName"))

    override protected def logEvent(bytes: Array[Byte], len: Int): Unit =
        os.write(bytes, 0, len)
}

