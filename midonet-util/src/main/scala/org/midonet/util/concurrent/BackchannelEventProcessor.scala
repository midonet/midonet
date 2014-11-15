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
import org.midonet.util.concurrent.WakerUpper.WaitContext

object BackchannelEventProcessor {
    val DEFAULT_RETRIES = 100
}

class BackchannelEventProcessor[T >: Null](ringBuffer: RingBuffer[T],
                                           eventHandler: EventPoller.Handler[T],
                                           backchannel: Backchannel,
                                           sequencesToTrack: Sequence*)
    extends EventProcessor with WaitContext {

    import BackchannelEventProcessor._

    private val running = new AtomicBoolean(false)
    private val poller = ringBuffer.newPoller(sequencesToTrack:_*)

    override def getSequence: Sequence =
        poller.getSequence

    override def halt(): Unit =
        running.set(false)

    override def isRunning: Boolean =
        running.get

    override def shouldWakeUp(): Boolean = {
        var i = 0
        while (i < sequencesToTrack.length) {
            if (sequencesToTrack(i).get() <= poller.getSequence.get())
                return false
            i += 1
        }
        ringBuffer.isPublished(poller.getSequence.get() + 1) ||
        backchannel.shouldProcess() ||
        !isRunning
    }

    override def run(): Unit = {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Thread is already running")
        }

        try {
            var retries = DEFAULT_RETRIES
            while (running.get()) {
                poller.poll(eventHandler) match {
                    case PollState.GATING | PollState.IDLE =>
                        backchannel.process()
                        retries = applyWait(retries)
                    case _ =>
                }
            }
        } finally {
            running.set(false)
        }
    }

    private def applyWait(counter: Int): Int = {
        if (counter > 100) {
            counter - 1
        } else if (counter > 0) {
            Thread.`yield`()
            counter - 1
        } else {
            park()
            DEFAULT_RETRIES
        }
    }
}
