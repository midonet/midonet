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

import java.io.OutputStream
import java.nio.ByteBuffer

import scala.concurrent.duration.Duration
import scala.util.Try
import scala.util.control.NonFatal

import com.lmax.disruptor.{EventHandler, ExceptionHandler, LifecycleAware}

import uk.co.real_logic.sbe.codec.java.DirectBuffer

import org.midonet.logging.rule.RuleLogEventBinarySerialization._
import org.midonet.logging.rule.{MessageHeader, RuleLogEvent => RuleLogEventEncoder}
import org.midonet.midolman.config.RuleLoggingConfig
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.logging.rule.DisruptorRuleLogEventChannel.RuleLogEvent
import org.midonet.midolman.management.RuleLogging
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr}
import org.midonet.util.UnixClock
import org.midonet.util.logging.{RollingOutputStream, SizeBasedRollingStaticHeaderBufferedOutputStream, TimeBasedRollingStaticHeaderBufferedOutputStream}

abstract class RuleLogEventHandler extends EventHandler[RuleLogEvent]
                                           with LifecycleAware
                                           with MidolmanLogging
                                           with ExceptionHandler {

    protected val headerEncoder = new MessageHeader
    protected val eventEncoder = new RuleLogEventEncoder

    private val eventBuffer = new DirectBuffer(new Array[Byte](BufferSize))
    private val ipBuffer = ByteBuffer.allocate(16)

    protected var os: OutputStream = null

    // When an exception occurs, disable for 60 seconds to avoid spamming the
    // log with hundreds of exceptions per second.
    private var disabledUntil: Long = 0
    private val MsPerMinute: Long = 60L * 1000L

    protected val clock = UnixClock()

    override def onEvent(event: RuleLogEvent, sequence: Long,
                         endOfBatch: Boolean): Unit = {
        log.debug("RuleLogEventHandler received {}", event)

        // Skip if logging is disabled.
        val now = clock.time
        if (disabledUntil > now) {
            log.debug("Rule logging disabled for {} more seconds due to " +
                      "prior error, not logging event.",
                      new java.lang.Long((disabledUntil - now) / 1000))
            return
        } else if (os == null) {
            log.debug("Output stream is null, not logging event.")
            return
        }

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
        eventEncoder.loggerId(0, event.loggerId.getMostSignificantBits)
        eventEncoder.loggerId(1, event.loggerId.getLeastSignificantBits)
        eventEncoder.time(clock.time)

        // Src/dst IP
        fillIpBuffer(event.srcIp)
        eventEncoder.putSrcIp(ipBuffer.array, 0, ipBuffer.position)
        fillIpBuffer(event.dstIp)
        eventEncoder.putDstIp(ipBuffer.array, 0, ipBuffer.position)

        eventEncoder.putMetadata(chain.metadata, 0, chain.metadata.length)

        os.write(eventBuffer.array, 0, eventEncoder.limit)

        // Flush on every statement when debug is enabled.
        if (log.underlying.isDebugEnabled)
            os.flush()
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

    override def onStart(): Unit = {
        log.debug("Starting RuleLogEventHandler")
    }

    override def onShutdown(): Unit = {
        log.debug("Stopping RuleLogEventHandler")
        if (os != null) {
            log.debug("Closing RuleLogEventHandler's output stream.")
            os.close()
            os = null
        }
    }

    def flush(): Unit

    override def handleEventException(ex: Throwable,
                                      sequence: Long,
                                      event: Any): Unit = {
        log.error("Exception handling rule log event. Disabling rule logging " +
                  "for 60 seconds", ex)
        disabledUntil = clock.time + MsPerMinute
    }


    override def handleOnShutdownException(ex: Throwable): Unit = {
        log.error("Exception shutting down RuleLogEventHandler.", ex)
    }

    override def handleOnStartException(ex: Throwable): Unit = {
        log.error("Exception starting up RuleLogEventHandler. Rule logging " +
                  "may not work.", ex)
    }
}

object FileRuleLogEventHandler {
    val LogDir = try {
        System.getProperty("midolman.log.dir",
                           "/var/log/midolman").stripSuffix("/")
    } catch {
        case NonFatal(ex) => "/var/log/midolman"
    }

    private val SizePrefixes = Map("b" -> 1L, "kb" -> (1L << 10),
                                   "mb" -> (1L << 20), "gb" -> (1L << 30),
                                   "tb" -> (1L << 40))
}

class FileRuleLogEventHandler(config: RuleLoggingConfig,
                              logDir: String = FileRuleLogEventHandler.LogDir)
    extends RuleLogEventHandler {
    import FileRuleLogEventHandler.SizePrefixes

    val logPath = logDir.stripSuffix("/") + '/' + config.logFileName

    override def onStart(): Unit = {
        log.debug("Starting FileRuleLogEventHandler")

        try {
            os = parseRotationFrequency(config.rotationFrequency) match {
                case Left(dur) =>
                    new TimeBasedRollingStaticHeaderBufferedOutputStream(
                        logPath, config.maxFiles, config.compress,
                        header, dur.toSeconds)
                case Right(size) =>
                    new SizeBasedRollingStaticHeaderBufferedOutputStream(
                        logPath, config.maxFiles,
                        config.compress, header, size)
            }
        } catch {
            case NonFatal(ex) =>
                log.error(s"Could not open log file $logPath for output. " +
                          "Rule events (e.g. firewall logging events) will " +
                          "not be logged.", logPath, ex)
                // os remains null
        }

        RuleLogging.setEventHandler(this)
        RuleLogging.registerAsMXBean()
    }

    private val header: Array[Byte] = {
        val buf = new DirectBuffer(new Array[Byte](headerEncoder.size))
        headerEncoder.wrap(buf, 0, MessageTemplateVersion)
            .blockLength(eventEncoder.sbeBlockLength())
            .templateId(eventEncoder.sbeTemplateId())
            .schemaId(eventEncoder.sbeSchemaId())
            .version(eventEncoder.sbeSchemaVersion())
        buf.array()
    }

    override def flush(): Unit = {
        if (os != null)
            os.flush()
    }

    def rotateLogs(): Unit = {
        os.asInstanceOf[RollingOutputStream].rollOver()
    }

    private def parseRotationFrequency(s: String): Either[Duration, Long] = {
        Try {
            // Try to parse as Duration first.
            Left(Duration(s))
        } recover {
            // If that fails, try to parse as size.
            case NonFatal(ex) => Right(parseSizeInterval(s))
        } recover {
            case NonFatal(ex) =>
                log.error("Could not parse rotation frequency: {}. " +
                          "Defaulting to once daily.", s)
                Left(Duration("1 day"))
        }
    }.get

    private def parseSizeInterval(s: String): Long = {
        val numStr = s.takeWhile(_.isDigit)
        val suffix = s.drop(numStr.length).dropWhile(!_.isLetter).toLowerCase
        SizePrefixes.get(suffix) match {
            case Some(factor) =>
                numStr.toLong * factor
            case None =>
                throw new IllegalArgumentException
        }
    }
}
