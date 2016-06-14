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

import java.util.UUID

import scala.util.control.NonFatal

import org.slf4j.{Logger, LoggerFactory}

import org.midonet.midolman.rules.Rule
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{AsyncAppender, LoggerContext}
import ch.qos.logback.core.FileAppender

private object RuleLogger {
    lazy val LogCtx =
        LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]

    // Encoder looks like it should be shareable, but is not. Each FileAppender
    // must have its own encoder instance, since it has an outputStream property
    // that Logback initializes when starting the FileAppender.
    def newEncoder: PatternLayoutEncoder = {
        val enc = new PatternLayoutEncoder
        enc.setContext(LogCtx)
        enc.setPattern("TIMESTAMP=%d{yyyy-MM-dd'T'HH:mm:ss.SSSZ} %msg%n")
        enc.start()
        enc
    }
}

trait RuleLogger extends VirtualDevice {
    val id: UUID
    protected val eventLog: Logger
    val logAcceptEvents: Boolean
    val logDropEvents: Boolean

    override def deviceTag: FlowTag = FlowTagger.tagForRuleLogger(id)

    protected val format =
        "SRC={} DST={} SPT={} DPT={} PROTO={} " +
        "CHAIN={} RULE={} MD=[{}] {}"

    def logAccept(pktCtx: PacketContext, chain: Chain, rule: Rule): Unit = {
        if (logAcceptEvents)
            logEvent(pktCtx, "ACCEPT", chain, rule)
    }

    def logDrop(pktCtx: PacketContext, chain: Chain, rule: Rule): Unit = {
        if (logDropEvents)
            logEvent(pktCtx, "DROP", chain, rule)
    }

    private def logEvent(pktCtx: PacketContext, event: String,
                         chain: Chain, rule: Rule): Unit = {
        val m = pktCtx.wcmatch
        eventLog.info(format,
                      m.getNetworkSrcIP, m.getNetworkDstIP,
                      Int.box(m.getSrcPort), Int.box(m.getDstPort),
                      Byte.box(m.getNetworkProto),
                      chain.id, rule.id, chain.metadata, event)
    }
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
    import RuleLogger._

    // Reuse old logger if possible.
    override protected val eventLog: Logger =
        if (oldLogger != null && logDir == oldLogger.logDir &&
            fileName == oldLogger.fileName) {
            oldLogger.eventLog
        } else {
            makeNewEventLogger
        }

    private def makeNewEventLogger: Logger = {
        val fileAppender = new FileAppender[ILoggingEvent]
        fileAppender.setContext(LogCtx)
        fileAppender.setName("FILE-" + id)
        fileAppender.setFile(s"$logDir/$fileName")
        fileAppender.setEncoder(newEncoder)
        fileAppender.start()

        val asyncAppender = new AsyncAppender
        asyncAppender.setContext(LogCtx)
        asyncAppender.setName("ASYNC-" + id)
        asyncAppender.addAppender(fileAppender)
        asyncAppender.start()

        val logger = LoggerFactory.getLogger("LoggingResource-" + id)
        logger.asInstanceOf[ch.qos.logback.classic.Logger].addAppender(asyncAppender)
        logger
    }

    override def toString: String =
        s"FileRuleLogger[id=$id, logDir=$logDir, fileName=$fileName, " +
        s"logAcceptEvents=$logAcceptEvents, logDropEvents=$logDropEvents]"
}

