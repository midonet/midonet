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

import java.util.concurrent.atomic.{AtomicReferenceArray, AtomicInteger}
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
        private[WakerUpper] var thread: Thread = _

        final def park(): Unit =
            while (!shouldWakeUp()) {
                WakerUpper.register(this)
                try {
                    LockSupport.park()
                } finally {
                    WakerUpper.deregister(this)
                }
            }

        /**
         * This function tells the WakerUpper whether the blocked thread should
         * be unparked. It should be thread-safe and side-effect free.
         */
        def shouldWakeUp(): Boolean
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

    private def register(ctx: Parkable): Unit = {
        ctx.thread = Thread.currentThread()
        waiters.set(threadId.get, ctx)
    }

    private def deregister(ctx: Parkable): Unit = {
        ctx.thread = null
        waiters.set(threadId.get, null) // Cleanup in case of a spurious wake up
    }

    private def wakerUpperLoop(): Unit =
        while (true) {
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
                        val thread = ctx.thread
                        if (thread ne null) {
                            LockSupport.unpark(thread)
                        }
                    }
                } catch { case ignored: Throwable => }
                i += 1
            }
            LockSupport.parkNanos(1L) // Sleeps around 50us on the latest Linux kernels
        }
}
