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

package org.midonet.midolman

import java.util.concurrent.TimeUnit

import com.lmax.disruptor._

import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.odp.Packet
import org.midonet.Util

trait PacketWorker {
    def submit(packet: Packet): Boolean
}

object DisruptorPacketWorker {
    val RingBufferCapacity =
        Util.findNextPositivePowerOfTwo(1 << 15) // 32768
    val WaitTimeoutMicros = 50
}

class DisruptorPacketWorker(packetWorkflow: PacketWorkflow,
                            metrics: PacketPipelineMetrics,
                            index: Int)
        extends Thread(s"packet-worker-${index}")
        with ExceptionHandler
        with PacketWorker with MidolmanLogging {
    override def logSource = s"org.midonet.packet-worker.packet-worker-$index"

    setDaemon(true)

    val ringBuffer = RingBuffer
        .createMultiProducer(PacketWorkflow.PacketRefFactory,
                             DisruptorPacketWorker.RingBufferCapacity,
                             new TimeoutBlockingWaitStrategy(
                                 DisruptorPacketWorker.WaitTimeoutMicros,
                                 TimeUnit.MICROSECONDS))
    val eventProcessor = new BatchEventProcessor(
        ringBuffer, ringBuffer.newBarrier(), packetWorkflow)
    ringBuffer.addGatingSequences(eventProcessor.getSequence())

    override def submit(packet: Packet): Boolean = {
        try {
            val seq = ringBuffer.tryNext()
            try {
                val ref = ringBuffer.get(seq)
                ref.packet = packet
            } finally {
                ringBuffer.publish(seq)
            }
            true
        } catch {
            case ice: InsufficientCapacityException =>
                log.debug("Disruptor ring buffer full, packet dropped")
                metrics.workerQueueOverflow.mark()
                false
        }
    }

    def isRunning() = eventProcessor.isRunning

    def shutdown(): Unit =
        eventProcessor.halt()

    def shutdownNow(): Unit = {
        shutdown()
        interrupt()
    }

    override def run(): Unit =
        eventProcessor.run()

    override def handleEventException(e: Throwable, sequence: Long,
                                      event: Object): Unit = {
        log.error("Packet worker crashed with exception, killing process", e)
        Midolman.exitAsync(
            Midolman.MIDOLMAN_ERROR_CODE_PACKET_WORKER_DIED)
    }

    override def handleOnStartException(ex: Throwable): Unit = {
        // Unused since we don't use LifecycleAware
    }

    override def handleOnShutdownException(ex: Throwable): Unit = {
        // Unused since we don't use LifecycleAware
    }
}
