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
import org.midonet.util.concurrent.WakerUpper.Parkable

object WakerUpperWaitStrategy {
    class SequenceParkable extends Parkable {
        var sequence: Long = _
        var dependentSequence: Sequence = _
        var barrier: SequenceBarrier = _

        def prepare(sequence: Long, dependentSequence: Sequence,
                    barrier: SequenceBarrier): Unit = {
            this.sequence = sequence
            this.dependentSequence = dependentSequence
            this.barrier = barrier
        }

        override def shouldWakeUp() =
            dependentSequence.get >= sequence || barrier.isAlerted
    }

    val waitContext = new ThreadLocal[SequenceParkable]() {
        override def initialValue = new SequenceParkable
    }
}

/**
 * WakerUpper based strategy for EventProcessors to wait on a sequence barrier.
 *
 * This strategy can be used when we don't want a pure spin based WaitStrategy,
 * and also when we don't want to burden producers with waking up all of the
 * Disruptor's EventProcessors. Moving the unparking to the WakerUpper means
 * there is some delay (around 50us in the worst case) before the EventProcessors
 * react to the advancing sequence.
 * Spins, then yields, then registers with the WakerUpper.
 */
class WakerUpperWaitStrategy(retries: Int = 200) extends WaitStrategy {
    import org.midonet.util.concurrent.WakerUpperWaitStrategy._

    override def waitFor(sequence: Long, cursor: Sequence,
                         dependentSequence: Sequence,
                         barrier: SequenceBarrier): Long = {
        var availableSequence = 0L
        var counter = retries
        while ({ availableSequence = dependentSequence.get
                 availableSequence } < sequence) {
            barrier.checkAlert()

            val waitCtx = waitContext.get()
            waitCtx.prepare(sequence, dependentSequence, barrier)
            counter = waitCtx.park(counter)
        }
        availableSequence
    }

    override def signalAllWhenBlocking(): Unit = { }
}
