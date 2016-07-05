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
import java.nio.ByteBuffer
import java.util.UUID

import scala.util.control.NonFatal

import uk.co.real_logic.sbe.codec.java.DirectBuffer

import org.midonet.logging.rule.RuleLogEventBinarySerialization.{BufferSize, MessageTemplateVersion}
import org.midonet.logging.rule.{MessageHeader, Result, RuleLogEvent}
import org.midonet.midolman.logging.rule.RuleLogEventChannel
import org.midonet.midolman.rules.Rule
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag

trait RuleLogger extends VirtualDevice {
    val id: UUID
    val logAcceptEvents: Boolean
    val logDropEvents: Boolean
    val eventChannel: RuleLogEventChannel

    protected val headerEncoder = new MessageHeader
    protected val eventEncoder = new RuleLogEvent

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
        eventChannel.handoff(id, m.getNetworkProto, chain, rule,
                             m.getNetworkSrcIP, m.getNetworkDstIP,
                             m.getSrcPort, m.getDstPort, result)
        m.doTrackSeenFields()
    }

    def close(): Unit
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
                          eventChannel: RuleLogEventChannel,
                          logDir: String = FileRuleLogger.DefaultLogDir)
                         (oldLogger: FileRuleLogger = null)
    extends RuleLogger {

    val path: String = s"$logDir/$fileName"

    private val os: BufferedOutputStream =
        new BufferedOutputStream(new FileOutputStream(s"$logDir/$fileName"))
    writeHeaderIfNeeded()
    eventChannel.registerStream(id, os)

    def flush(): Unit = {
        if (os != null) {
            os.flush()
        }
    }

    def close(): Unit = {
        if (os != null) {
            os.close()
        }
    }

    private def writeHeaderIfNeeded(): Unit = {
        val file = new File(s"$logDir/$fileName")
        if (!file.exists() || file.length() == 0) {
            val buf = new DirectBuffer(new Array[Byte](headerEncoder.size))
            headerEncoder.wrap(buf, 0, MessageTemplateVersion)
                .blockLength(eventEncoder.sbeBlockLength())
                .templateId(eventEncoder.sbeTemplateId())
                .schemaId(eventEncoder.sbeSchemaId())
                .version(eventEncoder.sbeSchemaVersion())

            os.write(buf.array(), 0, headerEncoder.size)
            os.flush()
        }
    }
}

