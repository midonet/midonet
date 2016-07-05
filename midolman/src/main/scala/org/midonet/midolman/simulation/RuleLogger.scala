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

import java.io.{BufferedOutputStream, File, FileOutputStream}
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

trait RuleLogger extends VirtualDevice {
    val id: UUID
    val logAcceptEvents: Boolean
    val logDropEvents: Boolean

    protected val HeaderEncoder = new MessageHeader
    protected val EventEncoder = new RuleLogEvent

    protected val eventBuffer = new DirectBuffer(new Array[Byte](BufferSize))
    private val ipBuffer = ByteBuffer.allocate(16)
    private val metadataBuffer = ByteBuffer.allocate(8192)

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
        m.doNotTrackSeenFields()

        EventEncoder.wrapForEncode(eventBuffer, 0)
            .srcPort(m.getSrcPort)
            .dstPort(m.getDstPort)
            .nwProto(m.getNetworkProto)
            .result(result)

        EventEncoder.chainId(0, chain.id.getMostSignificantBits)
        EventEncoder.chainId(1, chain.id.getLeastSignificantBits)
        EventEncoder.ruleId(0, rule.id.getMostSignificantBits)
        EventEncoder.ruleId(1, rule.id.getLeastSignificantBits)
        EventEncoder.time(System.currentTimeMillis)

        // Src/dst IP
        fillIpBuffer(m.getNetworkSrcIP)
        EventEncoder.putSrcIp(ipBuffer.array, 0, ipBuffer.position)
        fillIpBuffer(m.getNetworkDstIP)
        EventEncoder.putDstIp(ipBuffer.array, 0, ipBuffer.position)

        m.doTrackSeenFields()

        // Metadata.
        //
        // TODO: Consider serializing the whole metadata map as a single byte
        // array in the ChainMapper. However, this may make it harder to adapt
        // to supporting rule and chain metadata.
        metadataBuffer.position(0)
        var i = 0
        while (i < chain.metadata.size) {
            val entry = chain.metadata(i)
            metadataBuffer.putShort(entry._1.length.toShort)
            metadataBuffer.put(entry._1)
            metadataBuffer.putShort(entry._2.length.toShort)
            metadataBuffer.put(entry._2)
            i += 1
        }
        EventEncoder.putMetadata(metadataBuffer.array, 0,
                                 metadataBuffer.position)

        logEvent(eventBuffer.array(), EventEncoder.limit)
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

    def close(): Unit

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

    private var os: BufferedOutputStream = _
    if (oldLogger != null &&
        oldLogger.fileName == fileName && oldLogger.logDir == logDir) {
        os = oldLogger.releaseStream()
    } else {
        os = new BufferedOutputStream(new FileOutputStream(s"$logDir/$fileName"))
        writeHeaderIfNeeded()
    }

    // TODO: Flush to disk? Otherwise we may need to flush on exit.
    override protected def logEvent(bytes: Array[Byte], len: Int): Unit =
        os.write(bytes, 0, len)

    def flush(): Unit = {
        if (os != null)
            os.flush()
    }

    def close(): Unit = {
        if (os != null) {
            os.close()
        }
    }

    private def writeHeaderIfNeeded(): Unit = {
        val file = new File(s"$logDir/$fileName")
        if (!file.exists() || file.length() == 0) {
            HeaderEncoder.wrap(eventBuffer, 0, MessageTemplateVersion)
                .blockLength(EventEncoder.sbeBlockLength())
                .templateId(EventEncoder.sbeTemplateId())
                .schemaId(EventEncoder.sbeSchemaId())
                .version(EventEncoder.sbeSchemaVersion())

            os.write(eventBuffer.array(), 0, HeaderEncoder.size)
            os.flush()
        }
    }

    private def releaseStream(): BufferedOutputStream = {
        val osRef = os
        os = null
        osRef
    }
}

