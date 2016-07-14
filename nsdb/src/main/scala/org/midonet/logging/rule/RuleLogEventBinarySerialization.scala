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

package org.midonet.logging.rule

import java.io._
import java.nio.ByteBuffer
import java.nio.channels.FileChannel.MapMode.READ_ONLY
import java.nio.charset.Charset
import java.util.UUID

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

import uk.co.real_logic.sbe.codec.java.DirectBuffer

import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr}

object RuleLogEventBinarySerialization {
    val MessageTemplateVersion = 1
    val BufferSize = 8 * 1024

    val Utf8 = Charset.forName("UTF-8")

    def encodeMetadata(entries: Seq[(String, String)]): Array[Byte] = {
        val mdUtf8 = entries.map(
            e => (e._1.getBytes(Utf8), e._2.getBytes(Utf8)))
        val len = mdUtf8.foldLeft(0) {
            case (a, (k, v)) => a + 2 + k.length + 2 + v.length
        }
        val mdBuf = ByteBuffer.allocate(len)
        for ((k, v) <- mdUtf8) {
            mdBuf.putShort(k.length.toShort)
            mdBuf.put(k)
            mdBuf.putShort(v.length.toShort)
            mdBuf.put(v)
        }
        mdBuf.array()
    }

    def decodeMetadata(bytes: Array[Byte], len: Int): Seq[(String, String)] = {
        val bldr = new ListBuffer[(String, String)]
        val buf = ByteBuffer.wrap(bytes, 0, len)
        while (buf.hasRemaining) {
            val keyLen = buf.getShort
            val key = new String(buf.array(), buf.position, keyLen, Utf8)
            buf.position(buf.position + keyLen)

            val valueLen = buf.getShort
            val value = new String(buf.array(), buf.position, valueLen, Utf8)
            buf.slice()
            buf.position(buf.position + valueLen)
            bldr += ((key, value))
        }
        bldr.toList
    }
}

case class DeserializedRuleLogEvent(srcIp: IPAddr, dstIp: IPAddr,
                                    srcPort: Int, dstPort: Int,
                                    nwProto: Byte, result: String, time: Long,
                                    loggerId: UUID, chainId: UUID, ruleId: UUID,
                                    metadata: Seq[(String, String)]) {
}

private object RuleLogEventBinaryDeserializer {
    lazy val Utf8 = Charset.forName("UTF-8")
}

class RuleLogEventBinaryDeserializer(path: String) {
    import RuleLogEventBinarySerialization._

    protected val EventDecoder = new RuleLogEvent

    private val inFile = new RandomAccessFile(path, "r")
    private val inChannel = inFile.getChannel
    private val byteBuf = inChannel.map(READ_ONLY, 0, inChannel.size)
    private val directBuf = new DirectBuffer(byteBuf)

    private val metadataBuf = new Array[Byte](8192)
    private val ipBuffer = new Array[Byte](16)

    var pos = 0

    val header: FileHeader = try readHeader() catch {
        case NonFatal(ex) =>
            closeFile()
            throw ex
    }

    def hasNext: Boolean = pos < byteBuf.limit

    case class FileHeader(templateId: Int, schemaId: Int,
                          version: Int, blockLength: Int)
    private def readHeader(): FileHeader = {
        val h = new MessageHeader
        if (inFile.length() < h.size()) {
            throw new IllegalArgumentException(
                s"File $path is not a valid binary log file.")
        }

        h.wrap(directBuf, 0, MessageTemplateVersion)

        val templateId = h.templateId
        if (templateId != RuleLogEvent.TEMPLATE_ID) {
            throw new IllegalArgumentException(
                s"Template ID is $templateId, should be " +
                s"${RuleLogEvent.TEMPLATE_ID}.")
        }

        val schemaId = h.schemaId
        if (h.schemaId != RuleLogEvent.SCHEMA_ID) {
            throw new IllegalArgumentException(
                s"Schema ID is $schemaId, should be ${RuleLogEvent.SCHEMA_ID}.")
        }

        pos += h.size

        FileHeader(templateId, h.schemaId,
                   h.version, h.blockLength)
    }

    def next(): DeserializedRuleLogEvent = try {
        EventDecoder.wrapForDecode(directBuf, pos, header.blockLength,
                                   header.version)
        val srcIpLen = EventDecoder.getSrcIp(ipBuffer, 0, 16)
        val srcIp = parseIp(srcIpLen)
        val dstIpLen = EventDecoder.getDstIp(ipBuffer, 0, 16)
        val dstIp = parseIp(dstIpLen)

        val mdLen = EventDecoder.getMetadata(metadataBuf, 0, metadataBuf.length)
        val metadata = decodeMetadata(metadataBuf, mdLen)

        val loggerId = new UUID(EventDecoder.loggerId(0),
                                EventDecoder.loggerId(1))
        val chainId = new UUID(EventDecoder.chainId(0), EventDecoder.chainId(1))
        val ruleId = new UUID(EventDecoder.ruleId(0), EventDecoder.ruleId(1))

        pos = EventDecoder.limit

        DeserializedRuleLogEvent(srcIp, dstIp,
                                 EventDecoder.srcPort, EventDecoder.dstPort,
                                 EventDecoder.nwProto.toByte,
                                 EventDecoder.result.toString,
                                 EventDecoder.time,
                                 loggerId, chainId, ruleId, metadata)
    } catch {
        case ex: IndexOutOfBoundsException =>
            throw new IllegalArgumentException("Log file corrupt.")
    }

    private def parseIp(len: Int): IPAddr = len match {
        case 4 => IPv4Addr(ipBuffer.take(4))
        case 16 => IPv6Addr.fromBytes(ipBuffer)
    }

    def closeFile(): Unit = {
        inChannel.close()
        inFile.close()
    }
}
