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

import java.nio.channels.Selector
import java.util.concurrent.atomic.{AtomicInteger, AtomicReferenceArray}
import java.util.concurrent.locks.LockSupport

object WakerUpper {

    val MAX_THREADS_PROP_NAME = "waker_upper.max_threads"
    val MAX_THREADS = { val mt = System.getProperty(MAX_THREADS_PROP_NAME)
                        if (mt ne null) Integer.parseInt(mt) else 64 }

    /**
     * Trait through which threads park, registering themselves with the
     * WakerUpper. This thread periodically checks if the parked thread
     * has conditions to be unparked.
     */
    trait Parkable {
        private var thread: Thread = _

        final def park(retries: Int = 200): Int = {
            var remainingRetries = retries
            while (!shouldWakeUp()) {
                remainingRetries = applyParkMethod(remainingRetries)
            }
            remainingRetries
        }

        private def applyParkMethod(counter: Int): Int = {
            if (counter > 100) {
                counter - 1
            } else if (counter > 0) {
                Thread.`yield`()
                counter - 1
            } else {
                thread = Thread.currentThread()
                WakerUpper.register(this)
                try {
                    doPark()
                } finally {
                    WakerUpper.deregister(this)
                    thread = null
                }
                counter
            }
        }

        protected def doPark(): Unit =
            LockSupport.park()

        protected[WakerUpper] def doUnpark(): Unit = {
            val t = thread
            if (t ne null)
                LockSupport.unpark(t)
        }

        /**
         * This function tells the WakerUpper whether the blocked thread should
         * be unparked. It should be thread-safe and side-effect free.
         */
        protected[WakerUpper] def shouldWakeUp(): Boolean
    }

    trait SelectorParkable extends Parkable {
        protected def selector: Selector

        override protected def doPark(): Unit =
            selector.select()

        override protected[WakerUpper] def doUnpark(): Unit =
            selector.wakeup()
    }

    private val threadIdGenerator = new AtomicInteger()
    private val threadId = new ThreadLocal[Int] {
        override def initialValue() = {
            val id = threadIdGenerator.getAndIncrement
            if (id > MAX_THREADS) {
                throw new IllegalStateException(
                    s"Tried to register more than $MAX_THREADS threads. " +
                    "Please check the waker_upper.max_threads property")
            }
            id
        }
    }
    private val waiters = new AtomicReferenceArray[Parkable](MAX_THREADS)
    private val wakerThread = new Thread("waker-upper") {
        override def run() = wakerUpperLoop()
    }

    {
        wakerThread.setDaemon(true)
        wakerThread.start()
    }

    private def register(ctx: Parkable): Unit =
        waiters.set(threadId.get, ctx)

    private def deregister(ctx: Parkable): Unit =
        waiters.set(threadId.get, null)

    private def wakerUpperLoop(): Unit = while (true) {
        var i = 0
        while (i < waiters.length) {
            val ctx = waiters.get(i)
            try {
                if ((ctx ne null) && ctx.shouldWakeUp()) {
                    // We must remove the WaitContext from the array before
                    // waking up the thread because of the following ABA problem:
                    //   1) We wake up the thread;
                    //   2) The thread is scheduled, runs, and blocks again;
                    //   3) We remove the WaitContext from the array, and
                    //      won't ever wake up the thread.
                    waiters.set(i, null)
                    ctx.doUnpark()
                }
            } catch { case ignored: Throwable => }
            i += 1
        }
        LockSupport.parkNanos(1L) // Sleeps around 50us on the latest Linux kernels
    }
}
