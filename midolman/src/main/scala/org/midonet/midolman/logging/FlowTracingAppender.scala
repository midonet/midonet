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

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.datastax.driver.core.Session
import org.slf4j.LoggerFactory

import org.midonet.conf.HostIdGenerator
import org.midonet.util.concurrent.CallingThreadExecutionContext

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
    var lastAppendErrored = false

    var schema: FlowTracingSchema = null

    override def start(): Unit = {
        sessionFuture.onSuccess {
            case s =>
                schema = new FlowTracingSchema(s)
                session = s
        }(CallingThreadExecutionContext)
        super.start()
    }

    override def append(event: ILoggingEvent) {
        import FlowTracingContext._
        if (session ne null) {
            val mdc = event.getMDCPropertyMap

            val requestIds = mdc.get(TraceRequestIdKey).split(",")
            var i = requestIds.length - 1

            while (i >= 0) {
                val traceId = UUID.fromString(requestIds(i))
                val flowTraceId = UUID.fromString(mdc.get(FlowTraceIdKey))

                try {
                    session.execute(schema.bindFlowInsertStatement(
                                        traceId, flowTraceId,
                                        mdc.get(EthSrcKey), mdc.get(EthDstKey),
                                        intOrVal(mdc.get(EtherTypeKey), 0),
                                        mdc.get(NetworkSrcKey), mdc.get(NetworkDstKey),
                                        intOrVal(mdc.get(NetworkProtoKey), 0),
                                        intOrVal(mdc.get(SrcPortKey), 0),
                                        intOrVal(mdc.get(DstPortKey), 0)))
                    session.execute(schema.bindDataInsertStatement(
                                        traceId, flowTraceId,
                                        hostId, event.getFormattedMessage))
                    lastAppendErrored = false
                } catch {
                    case e: Throwable =>
                        if (!lastAppendErrored) {
                            log.error("Error sending trace data to cassandra", e)
                        }
                        lastAppendErrored = true
                }
                i -= 1
            }
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
