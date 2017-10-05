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

package org.midonet.midolman.logging

import java.util.UUID

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.datastax.driver.core.exceptions.DriverException
import com.datastax.driver.core.{Session, Statement}
import org.jctools.queues.SpmcArrayQueue
import org.slf4j.LoggerFactory

import org.midonet.conf.HostIdGenerator
import org.midonet.util.concurrent.CallingThreadExecutionContext
import org.midonet.util.concurrent.WakerUpper.Parkable

class FlowTracingAppender(sessionFuture: Future[Session])
        extends AppenderBase[ILoggingEvent] {

    val log = LoggerFactory.getLogger(classOf[FlowTracingAppender])
    val hostId = try {
        HostIdGenerator.getHostId
    } catch {
        case t: HostIdGenerator.PropertiesFileNotWritableException =>
            log.warn("Couldn't get host id, using random uuid")
            UUID.randomUUID()
    }

    @volatile
    var session: Session = null

    var schema: FlowTracingSchema = null

    /* Dedicated thread that sends messages to Cassandra in the same order as
     * they are emitted by the simulation thread (within a given simulation).
     */
    private val sender = new Thread("flow-tracing-appender") with Parkable {
        override def run(): Unit = while (true) {
            try {
                queue.poll() match {
                    case null => park()
                    case st => session execute st
                }
            } catch {
                case NonFatal(t: InterruptedException) =>
                    log.warn("Interrupted")
                    interrupt()
                case NonFatal(t: DriverException) =>
                    log.warn("Failed to send log message to Cassandra " +
                             s"${t.getMessage}")
                case NonFatal(t) =>
                    log.error("Unexpected error", t)
            }
        }
        override def shouldWakeUp(): Boolean = queue.peek() != null
    }

    val QueueSize = 100000
    private val queue = new SpmcArrayQueue[Statement](QueueSize)

    override def start(): Unit = {
        sender.setDaemon(true)
        sessionFuture.onComplete {
            case Success(s) =>
                schema = new FlowTracingSchema(s)
                session = s
                sender.start()
            case Failure(t) =>
                log.warn(s"Failed to start session to Cassandra: ${t.getMessage}")
        }(CallingThreadExecutionContext)
        super.start()
    }

    override def append(event: ILoggingEvent): Unit = if (schema != null) {
        import FlowTracingContext._
        val mdc = event.getMDCPropertyMap

        val requestIds = mdc.get(TraceRequestIdKey).split(",")
        var i = requestIds.length - 1

        while (i >= 0) {
            val traceId = UUID.fromString(requestIds(i))
            val flowTraceId = UUID.fromString(mdc.get(FlowTraceIdKey))

            try {

                val st1 = schema.bindFlowInsertStatement(
                    traceId, flowTraceId,
                    mdc.get(EthSrcKey), mdc.get(EthDstKey),
                    intOrVal(mdc.get(EtherTypeKey), 0),
                    mdc.get(NetworkSrcKey), mdc.get(NetworkDstKey),
                    intOrVal(mdc.get(NetworkProtoKey), 0),
                    intOrVal(mdc.get(SrcPortKey), 0),
                    intOrVal(mdc.get(DstPortKey), 0))

                val st2 = schema.bindDataInsertStatement(
                    traceId, flowTraceId,
                    hostId, event.getFormattedMessage)

                if (!queue.offer(st1) || !queue.offer(st2)) {
                    log.info("Backpressure triggered on queue of flow " +
                             "tracing events emitted to Cassandra (curr. " +
                             s"bound: $QueueSize)")
                }
            } catch {
                case e: Throwable =>
                    log.error("Error building statements", e)
            }
            i -= 1
        }
    }

    private def intOrVal(s: String, default: Int): Int = {
        if (s == null) {
            default
        } else {
            try {
                s.toInt
            } catch {
                case e: NumberFormatException => default
            }
        }
    }
}
