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
package org.midonet.midolman.monitoring

import java.io.IOException
import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, TimeUnit}

import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.{Random, Try}

import com.google.common.util.concurrent.AbstractService
import com.google.protobuf.CodedOutputStream
import com.lmax.disruptor._
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.Observer

import org.midonet.Util
import org.midonet.cluster.flowhistory.BinarySerialization
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.discovery.{MidonetDiscoveryClient, MidonetServiceHostAndPort}
import org.midonet.midolman.config.{FlowHistoryConfig, MidolmanConfig}
import org.midonet.util.concurrent.NamedThreadFactory
import org.midonet.util.functors.makeRunnable

trait FlowSenderWorker extends AbstractService {
    def submit(encodedFlow: ByteBuffer): Boolean
}

object FlowSenderWorker {
    def apply(config: MidolmanConfig, backend: MidonetBackend) = {
        if (config.flowHistory.enabled &&
            config.flowHistory.endpointService.nonEmpty) {
            new DisruptorFlowSenderWorker(config.flowHistory, backend)
        } else {
            NullFlowSenderWorker
        }
    }
}

object NullFlowSenderWorker extends FlowSenderWorker {
    override def submit(encodedFlow: ByteBuffer): Boolean = {
        false
    }

    override def doStart(): Unit = {
        notifyStarted()
    }

    override def doStop(): Unit = {
        notifyStopped()
    }
}

/**
  * Class responsible for sending flow records via TCP.
  */
class DisruptorFlowSenderWorker(config: FlowHistoryConfig,
                                backend: MidonetBackend)
    extends FlowSenderWorker {

    private val log = Logger(LoggerFactory.getLogger("org.midonet.history"))

    private val executor = Executors.newSingleThreadExecutor(
        new NamedThreadFactory("flow-history", true))

    private val ringBuffer = RingBuffer.createMultiProducer(
        new DisruptorFlowSenderWorker.ByteBufferFactory,
        Util.findNextPositivePowerOfTwo(config.queueSize),
        new BlockingWaitStrategy)

    private val flowSender = new FlowSender(config, backend)

    private val eventProcessor = new BatchEventProcessor(
        ringBuffer, ringBuffer.newBarrier(), flowSender)

    ringBuffer.addGatingSequences(eventProcessor.getSequence)

    override def submit(encodedFlow: ByteBuffer): Boolean = {
        try {
            val seq = ringBuffer.tryNext()
            try {
                val sendBuffer = ringBuffer.get(seq)
                sendBuffer.clear()
                sendBuffer.put(encodedFlow)
                sendBuffer.flip()
            } finally {
                ringBuffer.publish(seq)
            }
            true
        } catch {
            case ice: InsufficientCapacityException =>
                log.debug("Flow sender ring buffer full, packet dropped")
                false
        }
    }

    override def doStart(): Unit = {
        flowSender.startAsync().awaitRunning()
        executor.submit(makeRunnable {
            eventProcessor.run()
        })
        notifyStarted()
    }

    override def doStop(): Unit = {
        eventProcessor.halt()

        executor.shutdown()

        if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }

        flowSender.stopAsync().awaitTerminated()
        notifyStopped()
    }
}

class FlowSender(config: FlowHistoryConfig, backend: MidonetBackend)
    extends AbstractService with EventHandler[ByteBuffer] {

    private val log = Logger(LoggerFactory.getLogger("org.midonet.history"))

    private var clioDiscoveryClient: MidonetDiscoveryClient[MidonetServiceHostAndPort] = _

    private val endpointRef = new AtomicReference[Option[InetSocketAddress]](None)

    private var channel: SocketChannel = _
    private var current: InetSocketAddress = _
    private val sizeBuffer: ByteBuffer = ByteBuffer.allocateDirect(4)
    private val codedOutputStream = CodedOutputStream.newInstance(sizeBuffer, 4)

    def endpoint: Option[InetSocketAddress] = endpointRef.get

    override def onEvent(event: ByteBuffer, sequence: Long,
                         endOfBatch: Boolean): Unit = {
        try {
            sendRecord(event)
        } catch {
            case ex: IOException =>
                // Close channel on IOException
                close()
                log.info("Error sending flow record to endpoint", ex)
            case NonFatal(e) =>
                log.info("Unknown error while recording flow record", e)
        }
    }

    override def doStart(): Unit = {
        clioDiscoveryClient =
            backend.discovery.getClient[MidonetServiceHostAndPort](
                config.endpointService)
        subscribeToDiscovery()
        notifyStarted()
    }

    override def doStop(): Unit = {
        clioDiscoveryClient.stop()
        notifyStopped()
    }

    protected def sendRecord(buffer: ByteBuffer) = {
        val actualEndpoint = endpoint.orNull
        if (actualEndpoint != null) {
            maybeConnect(actualEndpoint)
            // Send buffer length int before sending buffer
            sizeBuffer.clear()
            codedOutputStream.writeRawVarint32(buffer.limit)
            codedOutputStream.flush()
            sizeBuffer.flip()
            while (sizeBuffer.hasRemaining)
                channel.write(sizeBuffer)
            while (buffer.hasRemaining)
                channel.write(buffer)
        }
    }

    private def subscribeToDiscovery() = {
        // Update endpoint as we discover more/less clio nodes.
        clioDiscoveryClient.observable.subscribe(
            new Observer[Seq[MidonetServiceHostAndPort]] {
                override def onCompleted(): Unit = {
                    log.debug("Service discovery completed for {}",
                              config.endpointService)
                    endpointRef.lazySet(None)
                }

                override def onError(e: Throwable): Unit = {
                    log.error("Error on {} service discovery",
                              config.endpointService)
                    endpointRef.lazySet(None)
                }

                override def onNext(t: Seq[MidonetServiceHostAndPort]): Unit = {
                    val chosenEndpoint =
                        if (t.nonEmpty) {
                            val randomEndpoint = t(Random.nextInt(t.length))
                            try {
                                Some(new InetSocketAddress(
                                    randomEndpoint.address,
                                    randomEndpoint.port))
                            } catch {
                                case t: Throwable =>
                                    log.warn(
                                        "Invalid endpoint: " + randomEndpoint,
                                        t)
                                    None
                            }
                        } else
                            None
                    endpointRef.lazySet(chosenEndpoint)
                    log.debug("New endpoint chosen: {}" + chosenEndpoint)
                }
            }
        )
    }

    private def maybeConnect(address: InetSocketAddress) = {
        if (channel == null || current != address) {
            close()
            current = address
            channel = SocketChannel.open()
            val connectionTimeoutMillis =
                FlowSender.ConnectionTimeout.toMillis.toInt
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE,
                              true: java.lang.Boolean)
            channel.socket.connect(address, connectionTimeoutMillis)
            log.debug("FlowRecordSender connected to {}", current)
        }
    }

    private def close() = {
        if (channel != null) {
            Try(channel.shutdownOutput()) // eat up shutdown errors
            Try(channel.close()) // eat up close errors
            channel = null
        }
    }
}

object DisruptorFlowSenderWorker {
    class ByteBufferFactory extends EventFactory[ByteBuffer] {
        override def newInstance(): ByteBuffer =
            ByteBuffer.allocateDirect(BinarySerialization.BufferSize)
    }
}

object FlowSender {
    final val ConnectionTimeout = Duration("5s")
}
