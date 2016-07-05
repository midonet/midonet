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

import java.io.{BufferedOutputStream, File, FileOutputStream, OutputStream}
import java.nio.ByteBuffer

import scala.util.control.NonFatal

import com.lmax.disruptor.{EventHandler, LifecycleAware}

import uk.co.real_logic.sbe.codec.java.DirectBuffer

import org.midonet.logging.rule.RuleLogEventBinarySerialization._
import org.midonet.logging.rule.{MessageHeader, RuleLogEventBinaryDeserializer, RuleLogEvent => RuleLogEventEncoder}
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.logging.rule.DisruptorRuleLogEventChannel.RuleLogEvent
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr}

abstract class RuleLogEventHandler extends EventHandler[RuleLogEvent]
                                           with LifecycleAware
                                           with MidolmanLogging {

    protected val headerEncoder = new MessageHeader
    protected val eventEncoder = new RuleLogEventEncoder

    private val eventBuffer = new DirectBuffer(new Array[Byte](BufferSize))
    private val ipBuffer = ByteBuffer.allocate(16)

    protected var os: OutputStream = null

    override def onEvent(event: RuleLogEvent, sequence: Long,
                         endOfBatch: Boolean): Unit = {
        if (os == null) return

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

    override def onStart(): Unit = {}

    override def onShutdown(): Unit = {
        if (os != null) {
            os.close()
        }
    }

    def flush(): Unit
}

object FileRuleLogEventHandler {
    val LogDir = try {
        System.getProperty("midolman.log.dir", "/var/log/midolman")
    } catch {
        case NonFatal(ex) => "/var/log/midolman"
    }

    val LogPath = LogDir + "/rule-binlog.rlg"
}

class FileRuleLogEventHandler(logPath: String = FileRuleLogEventHandler.LogPath)
    extends RuleLogEventHandler {

    override def onStart(): Unit = {
        val headerFound = try checkHeader() catch {
            case NonFatal(ex) =>
                // File exists but doesn't have valid header. Don't write
                // anything, to avoid corrupting what's already there.

                // TODO: Backup plan? Maybe periodically recheck?
                log.error(s"File $logPath exists, but does not have a valid " +
                          "header. Will not be able to log rule events (e.g. " +
                          "firewall logging). To enable firewall logging, " +
                          "delete or move this file and restart Midolman.", ex)
                return
        }

        try {
            os = new BufferedOutputStream(new FileOutputStream(logPath))
        } catch {
            case NonFatal(ex) =>
                log.error(s"Could not open log file $logPath for output. " +
                          "Rule events (e.g. firewall logging events) will " +
                          "not be logged.", logPath, ex)
                return // os remains null
        }

        if (!headerFound)
            writeHeader()
    }

    private def writeHeader(): Unit = {
        val buf = new DirectBuffer(new Array[Byte](headerEncoder.size))
        headerEncoder.wrap(buf, 0, MessageTemplateVersion)
            .blockLength(eventEncoder.sbeBlockLength())
            .templateId(eventEncoder.sbeTemplateId())
            .schemaId(eventEncoder.sbeSchemaId())
            .version(eventEncoder.sbeSchemaVersion())

        os.write(buf.array(), 0, headerEncoder.size)
        os.flush()
    }

    /**
      * Check log file header.
      *
      * @return true if the header exists and is valid with the correct version,
      *         false if the file is empty.
      * @throws IllegalArgumentException if the header is invalid or has an
      *                                  unexpected version.
      */
    private def checkHeader(): Boolean = {
        val file = new File(logPath)
        if (file.length() == 0) {
            false
        } else {
            val deserializer = new RuleLogEventBinaryDeserializer(logPath)
            deserializer.closeFile()
            true
        }
    }

    override def flush(): Unit = {
        if (os != null)
            os.flush()
    }
}
