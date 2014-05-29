/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
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
