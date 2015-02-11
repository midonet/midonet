/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.util.concurrent

import java.util.concurrent.atomic.AtomicBoolean

import com.lmax.disruptor.EventPoller.PollState
import com.lmax.disruptor._

import org.midonet.util.concurrent.WakerUpper.Parkable

object BackchannelEventProcessor {
    val DEFAULT_RETRIES = 200
}

/**
 * An EventProcessor that handles the batching semantics of consuming entries
 * from a RingBuffer, delegating the available events to the specified eventHandler,
 * and responds to work from a Backchannel. This EventProcessor is gated on the
 * supplied sequences. When there are no events or work from the Backchannel, it
 * registers the underlying thread with WakerUpper.
 */
class BackchannelEventProcessor[T >: Null](ringBuffer: RingBuffer[T],
                                           eventHandler: EventPoller.Handler[T],
                                           backchannel: Backchannel,
                                           sequencesToTrack: Sequence*)
    extends EventProcessor with Parkable {

    import BackchannelEventProcessor._

    private val running = new AtomicBoolean(false)
    private val poller = ringBuffer.newPoller(sequencesToTrack:_*)

    var exceptionHandler = new FatalExceptionHandler

    override def getSequence: Sequence =
        poller.getSequence

    override def halt(): Unit =
        running.set(false)

    override def isRunning: Boolean =
        running.get

    override def shouldWakeUp(): Boolean = {
        if (backchannel.shouldProcess() || !isRunning) {
            return true
        }

        var i = 0
        while (i < sequencesToTrack.length) {
            if (sequencesToTrack(i).get() <= poller.getSequence.get())
                return false
            i += 1
        }
        ringBuffer.isPublished(poller.getSequence.get() + 1)
    }

    override def run(): Unit = {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Thread is already running")
        }

        notifyStart()

        try {
            var retries = DEFAULT_RETRIES
            while (running.get()) {
                poller.poll(eventHandler) match {
                    case PollState.GATING | PollState.IDLE =>
                        retries = park(retries)
                    case _ =>
                        retries = DEFAULT_RETRIES
                }
                backchannel.process()
            }
        } finally {
            notifyShutdown()
            running.set(false)
        }
    }

    private def notifyStart(): Unit =
        eventHandler match {
            case aware: LifecycleAware =>
                try {
                    aware.onStart()
                } catch { case ex: Throwable =>
                    exceptionHandler.handleOnStartException(ex)
                }
            case _ =>
        }

    private def notifyShutdown(): Unit =
        eventHandler match {
            case aware: LifecycleAware =>
                try {
                    aware.onShutdown()
                } catch { case ex: Throwable =>
                    exceptionHandler.handleOnShutdownException(ex)
                }
            case _ =>
        }
}
