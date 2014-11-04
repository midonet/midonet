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

import com.lmax.disruptor.{Sequence, SequenceBarrier, WaitStrategy}
import org.midonet.util.concurrent.WakerUpper.WaitContext

object WakerUpperWaitStrategy {
    class SequenceWaitContext extends WaitContext {
        var sequence: Long = _
        var dependentSequence: Sequence = _
        var barrier: SequenceBarrier = _

        def init(sequence: Long, dependentSequence: Sequence,
                 barrier: SequenceBarrier): Unit = {
            this.sequence = sequence
            this.dependentSequence = dependentSequence
            this.barrier = barrier
        }

        override def shouldWakeUp() =
            dependentSequence.get >= sequence || barrier.isAlerted
    }

    val waitContext = new ThreadLocal[SequenceWaitContext]() {
        override def initialValue = new SequenceWaitContext
    }
}

class WakerUpperWaitStrategy(retries: Int = 200) extends WaitStrategy {
    import org.midonet.util.concurrent.WakerUpperWaitStrategy._

    override def waitFor(sequence: Long, cursor: Sequence,
                         dependentSequence: Sequence,
                         barrier: SequenceBarrier): Long = {
        var availableSequence = 0L
        var counter = retries
        while ({ availableSequence = dependentSequence.get
                 availableSequence } < sequence) {
            counter = applyWaitMethod(sequence, dependentSequence, barrier, counter)
        }
        availableSequence
    }

    private def applyWaitMethod(sequence: Long, dependentSequence: Sequence,
                                barrier: SequenceBarrier, counter: Int): Int = {
        barrier.checkAlert()
        if (counter > 100) {
            counter - 1
        } else if (counter > 0) {
            Thread.`yield`()
            counter - 1
        } else {
            val waitCtx = waitContext.get()
            waitCtx.init(sequence, dependentSequence, barrier)
            waitCtx.park()
            counter
        }
    }

    override def signalAllWhenBlocking(): Unit = { }
}
