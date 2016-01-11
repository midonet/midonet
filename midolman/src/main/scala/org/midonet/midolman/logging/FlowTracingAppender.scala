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
import java.util.concurrent.{Executor, Executors}

import scala.collection.JavaConversions._
import scala.concurrent.Future

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.datastax.driver.core.{BatchStatement, Session}
import org.slf4j.LoggerFactory
import rx.Observer
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject

import org.midonet.conf.HostIdGenerator
import org.midonet.util.concurrent.{CallingThreadExecutionContext, NamedThreadFactory}
import org.midonet.util.functors.makeAction1

class FlowTracingAppender(sessionFuture: Future[Session])
        extends AppenderBase[ILoggingEvent] {

    val log = LoggerFactory.getLogger(classOf[FlowTracingAppender])
    val hostId = try {
        HostIdGenerator.getHostId
    } catch {
        case t: HostIdGenerator.PropertiesFileNotWritableException => {
            log.warn("Couldn't get host id, using random uuid")
            UUID.randomUUID()
        }
    }

    @volatile
    var session: Session = null

    var schema: FlowTracingSchema = null

    private val executor: Executor = Executors.newSingleThreadExecutor(
        new NamedThreadFactory("flow-tracing-appender", isDaemon = true)
    )

    /* This is per-se an unbounded queue, but we'll achieve the bounds using
     * the observeOn operator below (which has a 128 slot internal buffer).
     */
    private val queue = PublishSubject.create[ILoggingEvent]()

    /** Consumer of the queue of [[ILoggingEvent]] that will synchronously
      * push items to Cassandra, guaranteeing that the local order matches
      * that of the events stored in Cassandra.
      *
      * The backpressure policy is to DROP events if it cannot keep up.
      */
    private val loggingEventObserver = new Observer[ILoggingEvent] {
        override def onCompleted(): Unit = {
            log.error("Unexpected, the FlowTracingAppender should never emit " +
                      "an onComplete")
        }
        override def onError(e: Throwable): Unit = {
            log.error("Unexpected, the FlowTracingAppender should never emit " +
                      "an onError", e)
        }
        override def onNext(e: ILoggingEvent): Unit = syncSendToCassandra(e)
    }

    /** Log a warning alerting that we're dropping events as the Cassandra
      * thread cannot keep up.
      */
    private val onDrop = makeAction1[ILoggingEvent] { e =>
        log.info(s"Backpressure triggered - dropping $e")
    }

    override def start(): Unit = {
        queue.onBackpressureDrop(onDrop)
             .observeOn(Schedulers.from(executor))
             .subscribe(loggingEventObserver)
        sessionFuture.onSuccess {
            case s =>
                schema = new FlowTracingSchema(s)
                session = s
        }(CallingThreadExecutionContext)
        super.start()
    }

    override def append(event: ILoggingEvent): Unit = queue.onNext(event)

    /** Synchronously sends the event to Cassandra, error handling included.
      */
    private def syncSendToCassandra(event: ILoggingEvent) {
        import FlowTracingContext._

        if (session ne null) {
            val mdc = event.getMDCPropertyMap

            val requestIds = mdc.get(TraceRequestIdKey).split(",")
            var i = requestIds.length - 1

            while (i >= 0) {
                val traceId = UUID.fromString(requestIds(i))
                val flowTraceId = UUID.fromString(mdc.get(FlowTraceIdKey))

                try {
                    val batch = new BatchStatement()
                    batch.addAll(Seq(
                        schema.bindFlowInsertStatement(
                            traceId, flowTraceId,
                            mdc.get(EthSrcKey), mdc.get(EthDstKey),
                            intOrVal(mdc.get(EtherTypeKey), 0),
                            mdc.get(NetworkSrcKey), mdc.get(NetworkDstKey),
                            intOrVal(mdc.get(NetworkProtoKey), 0),
                            intOrVal(mdc.get(SrcPortKey), 0),
                            intOrVal(mdc.get(DstPortKey), 0)),
                        schema.bindDataInsertStatement(
                            traceId, flowTraceId,
                            hostId, event.getFormattedMessage)
                    ))
                    session.execute(batch)
                } catch {
                    case e: Throwable =>
                        log.error("Error sending trace data to cassandra", e)
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
