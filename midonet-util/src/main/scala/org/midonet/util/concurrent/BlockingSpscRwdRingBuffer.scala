/*
 * Copyright 2015 Midokura SARL
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

import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean}
import java.util.concurrent.locks.ReentrantLock

import scala.reflect.ClassTag

/**
 * A version of the rewindable buffer with blocking reads
 */
class BlockingSpscRwdRingBuffer[T: ClassTag](minCapacity: Int)
    extends SpscRwdRingBuffer[T](minCapacity) {
    import org.midonet.util.concurrent.SpscRwdRingBuffer._

    private val exit = new AtomicBoolean(false)
    private val paused = new AtomicBoolean(false)
    private val pending = new AtomicInteger(0)
    private val mutex = new ReentrantLock()
    private val readable = mutex.newCondition()

    override def offer(item: T): Boolean = {
        val success = super.offer(item)
        if (success && pending.getAndIncrement() == 0)
            Locks.withLock(mutex) {readable.signal()}
        success
    }

    override def poll(): Option[SequencedItem[T]] = {
        val value = super.poll()
        if (value.nonEmpty)
            pending.decrementAndGet()
        value
    }

    override def rewind(pos: Long): Boolean = {
        val cur = curSeqno
        (cur == pos) || {
            val success = super.rewind(pos)
            if (success)
                // Note: it's not necessary to signal readable,
                // because rewind must be called by the reader thread
                // (so, no one to awake)
                pending.getAndAdd((cur - pos).toInt)
            success
        }
    }

    private def awaitReadable(): Boolean = {
        Locks.withLock(mutex) {
            while (pending.get == 0 && !exit.get && !paused.get)
                readable.await()
        }
        !paused.get
    }

    /**
     * Wait until the exit or pause flags are set, or a value is available.
     * If the pause flag is not set, it extracts the value from the buffer,
     * if any.
     * @return None if the pause flag is set, or the result of poll otherwise
     */
    def awaitPoll(): Option[SequencedItem[T]] =
        if (awaitReadable()) poll() else None

    /** Wait until the exit or pause flags are set, or a value is available.
      * If the pause flag is not set, it extracts the value from the buffer,
      * if any.
      * @return None if the pause flag is set, or the result of peek otherwise
      */
    def awaitPeek(): Option[SequencedItem[T]] =
        if (awaitReadable()) peek else None

    /**
     * Notify that no more values will be added. This prevents blocking
     * on peek/poll.
     */
    def complete(): Unit = {
        exit.set(true)
        Locks.withLock(mutex) {readable.signal()}
    }

    /**
     * Check if no more values will be added
     * @return
     */
    def isComplete: Boolean = exit.get

    /**
     * Set the pause flag and awake the reader (if blocked)
     */
    def pauseRead(): Unit = {
        paused.set(true)
        Locks.withLock(mutex) {readable.signal()}
    }

    /**
     * Reset the pause flag
     */
    def resumeRead(): Unit = paused.set(false)

    /**
     * Get the pause flag
     */
    def isPaused: Boolean = paused.get()
}
