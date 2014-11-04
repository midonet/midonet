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

import java.util.concurrent.{TimeUnit, CountDownLatch}

import com.lmax.disruptor.{Sequence, AlertException, SequenceBarrier}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class WakerUpperWaitStrategyTest extends FeatureSpec with Matchers {

    class DummySequenceBarrier extends SequenceBarrier {
        var isAlerted = false
        override def alert(): Unit =  isAlerted = true
        override def clearAlert(): Unit = isAlerted = false
        override def checkAlert(): Unit = if (isAlerted) throw AlertException.INSTANCE
        override def waitFor(sequence: Long): Long = 0
        override def getCursor: Long = 0
    }

    feature ("WakerUpperWaitStrategy waits until conditions are met") {
        val waitStrategy = new WakerUpperWaitStrategy
        scenario ("wait ends when the sequence advances") {
            val dependentSequence = new Sequence(0)
            val latch = new CountDownLatch(1)
            new Thread() {
                override def run(): Unit = {
                    waitStrategy.waitFor(1L, null, dependentSequence, new DummySequenceBarrier)
                    latch.countDown()
                }
            } start()
            latch.await(300, TimeUnit.MILLISECONDS)
            latch.getCount should be (1)
            dependentSequence.incrementAndGet()
            latch.await()
            latch.getCount should be (0)
        }

        scenario ("Wait ends when the SequenceBarrier is alerted") {
            val barrier = new DummySequenceBarrier
            val latch = new CountDownLatch(1)
            new Thread() {
                override def run(): Unit = {
                    intercept[AlertException] {
                        waitStrategy.waitFor(1L, null, new Sequence(0), barrier)
                    }
                    latch.countDown()
                }
            } start()
            latch.await(300, TimeUnit.MILLISECONDS)
            latch.getCount should be (1)
            barrier.alert()
            latch.await()
            latch.getCount should be (0)
        }
    }
}
