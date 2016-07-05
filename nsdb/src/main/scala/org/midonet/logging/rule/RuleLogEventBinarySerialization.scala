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

package org.midonet.logging.rule

import java.io._
import java.nio.ByteBuffer
import java.nio.channels.FileChannel.MapMode.READ_ONLY
import java.nio.charset.Charset
import java.util.UUID

import scala.collection.mutable.ListBuffer

import uk.co.real_logic.sbe.codec.java.DirectBuffer

import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr}

object RuleLogEventBinarySerialization {
    val MessageTemplateVersion = 1
    val BufferSize = 16 * 1024
}

object DeserializedRuleLogEvent {
    private val format =
        "SRC={} DST={} SPT={} DPT={} PROTO={} " +
        "CHAIN={} RULE={} MD=[{}] {}"
}

case class DeserializedRuleLogEvent(srcIp: IPAddr, dstIp: IPAddr,
                                    srcPort: Int, dstPort: Int,
                                    nwProto: Byte, result: String, time: Long,
                                    chainId: UUID, ruleId: UUID,
                                    metadata: Seq[(String, String)]) {
}

private object RuleLogEventBinaryDeserializer {
    lazy val Utf8 = Charset.forName("UTF-8")
}

class RuleLogEventBinaryDeserializer(path: String) {
    import RuleLogEventBinaryDeserializer._
    import RuleLogEventBinarySerialization._

    protected val EventDecoder = new RuleLogEvent

    private val inFile = new RandomAccessFile(path, "r")
    private val inChannel = inFile.getChannel
    private val byteBuf = inChannel.map(READ_ONLY, 0, inChannel.size)
    private val directBuf = new DirectBuffer(byteBuf)

    private val metadataBuf = new Array[Byte](8192)
    private val stringBytesBuf = new Array[Byte](1024)
    private val ipBuffer = new Array[Byte](16)

    var pos = 0

    private val header: FileHeader = readHeader()

    def hasNext: Boolean = pos < byteBuf.limit

    case class FileHeader(templateId: Int, schemaId: Int,
                          version: Int, blockLength: Int)
    private def readHeader(): FileHeader = {
        val header = new MessageHeader
        header.wrap(directBuf, 0, MessageTemplateVersion)
        val templateId = header.templateId
        if (templateId != RuleLogEvent.TEMPLATE_ID) {
            throw new IllegalArgumentException(
                s"Template ID is $templateId, should be " +
                RuleLogEvent.TEMPLATE_ID)
        }
        pos += header.size

        FileHeader(templateId, header.schemaId,
                   header.version, header.blockLength)
    }

    def next(): DeserializedRuleLogEvent = {
        EventDecoder.wrapForDecode(directBuf, pos, header.blockLength,
                                   header.version)
        val srcIpLen = EventDecoder.getSrcIp(ipBuffer, 0, 16)
        val srcIp = parseIp(srcIpLen)
        val dstIpLen = EventDecoder.getDstIp(ipBuffer, 0, 16)
        val dstIp = parseIp(dstIpLen)

        val mdLen = EventDecoder.getMetadata(metadataBuf, 0, metadataBuf.length)
        val metadata = parseMetadata(mdLen)

        val chainId = new UUID(EventDecoder.chainId(0), EventDecoder.chainId(1))
        val ruleId = new UUID(EventDecoder.ruleId(0), EventDecoder.ruleId(1))

        pos = EventDecoder.limit

        DeserializedRuleLogEvent(srcIp, dstIp,
                                 EventDecoder.srcPort, EventDecoder.dstPort,
                                 EventDecoder.nwProto.toByte,
                                 EventDecoder.result.toString,
                                 EventDecoder.time, chainId, ruleId, metadata)
    }

    private def parseIp(len: Int): IPAddr = len match {
        case 4 => IPv4Addr(ipBuffer.take(4))
        case 16 => IPv6Addr.fromBytes(ipBuffer)
    }

    private def parseMetadata(mdLen: Int): Seq[(String, String)] = {
        val bldr = new ListBuffer[(String, String)]
        val buf = ByteBuffer.wrap(metadataBuf, 0, mdLen)
        while (buf.hasRemaining) {
            val keyLen = buf.getShort
            buf.get(stringBytesBuf, 0, keyLen)
            val key = new String(stringBytesBuf, 0, keyLen, Utf8)

            val valueLen = buf.getShort
            buf.get(stringBytesBuf, 0, valueLen)
            val value = new String(stringBytesBuf, 0, valueLen, Utf8)
            bldr += ((key, value))
        }
        bldr.toList
    }

    def closeFile(): Unit = {
        inChannel.close()
        inFile.close()
    }
}
