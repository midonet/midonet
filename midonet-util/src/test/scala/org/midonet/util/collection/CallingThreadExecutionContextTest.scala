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
package org.midonet.util.collection

import java.util.concurrent.locks.LockSupport

import scala.concurrent.{TimeoutException, ExecutionContext}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, FeatureSpec}

import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class CallingThreadExecutionContextTest extends FeatureSpec with Matchers {
    feature("CallingThreadExecutionContext executes Runnables on the calling thread") {
        scenario("the reentrancy counter is not exceeded") {
            var otherTid = -1L

            ExecutionContext.callingThread.execute(new Runnable {
                def run() {
                    otherTid = Thread.currentThread().getId
                }
            })

            Thread.currentThread().getId should be (otherTid)
        }

        scenario("the reentrancy counter is exceeded") {
            import CallingThreadExecutionContext.maxReentrancyAllowed
            val t = Thread.currentThread()
            @volatile var unparked = false

            def schedule(cnt: Int) {
                ExecutionContext.callingThread.execute(new Runnable {
                    def run() {
                        val tid = Thread.currentThread().getId
                        if (cnt == maxReentrancyAllowed) {
                            tid should not be t.getId
                            unparked = true
                            LockSupport.unpark(t)
                        } else {
                            tid should be (t.getId)
                            schedule(cnt + 1)
                        }
                    }
                })
            }

            schedule(0)
            parkUntil { unparked }
        }
    }

    private[this] def parkUntil(pred: => Boolean) {
        val deadline = System.currentTimeMillis() + 500
        do {
            LockSupport.parkUntil(deadline)
            if (System.currentTimeMillis() > deadline) {
                throw new TimeoutException()
            }
        } while (!pred)
    }
}
