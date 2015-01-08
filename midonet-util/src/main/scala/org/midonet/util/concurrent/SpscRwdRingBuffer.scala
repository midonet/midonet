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

import java.util.concurrent.atomic.AtomicLong

import scala.reflect.ClassTag

object SpscRwdRingBuffer {
    class BufferFullException
        extends IllegalStateException("Ring buffer is full")

    class BufferEmptyException
        extends IllegalStateException("Ring buffer is empty")

    class NotInBufferException
        extends NoSuchElementException("Ring buffer element unavailable")
}

/**
 * A ring buffer providing absolute sequence numbers for the items emitted,
 * making possible to 'rewind' the read pointer to a previous position, as
 * far as it doesn't exceed the ring buffer capacity.
 * NOTE: it assumes a single producer thread (or, at least,
 * no simultaneous 'offers' from different threads), and a single consumer
 * thread.
 *
 * @param minCapacity is the minimum capacity of the ring buffer
 * @tparam T is the type of the elements in the ring buffer
 */
class SpscRwdRingBuffer[T: ClassTag](minCapacity: Int) {
    import SpscRwdRingBuffer._
    type SequencedItem = (Long, T)

    private val capacity: Int =
        1 << (32 - Integer.numberOfLeadingZeros(minCapacity - 1))
    private val mask = capacity - 1
    private val ring = new Array[T](capacity)
    private val wrIntent = new AtomicLong(0) // write reservation
    private val wr = new AtomicLong(0)
    private val rd = new AtomicLong(0)

    /**
     * Insert an item in the ring buffer
     */
    def offer(item: T): Boolean = {
        val wrPos = wrIntent.getAndIncrement()
        if ((wrPos - rd.get()) >= capacity) {
            wrIntent.lazySet(wrPos)
            false
        } else {
            ring((wrPos & mask).toInt) = item
            wr.lazySet(wrPos + 1)
            true
        }
    }

    def add(item: T): Boolean = if (offer(item)) true else {
        throw new BufferFullException
    }

    /**
     * Get the next element in the ring buffer, if any, without extracting it
     */
    def peek(): Option[SequencedItem] = {
        val rdPos = rd.get()
        if (wr.get() == rdPos)
            None
        else
            Some(rdPos, ring((rdPos & mask).toInt))
    }

    def element(): SequencedItem = peek() match {
        case None => throw new NotInBufferException
        case Some(entry) => entry
    }

    /**
     * Extract the next element from the ring buffer, if any
     */
    def poll(): Option[SequencedItem] = {
        val rdPos = rd.get()
        if (wr.get() == rdPos)
            None
        else {
            val item = (rdPos, ring((rdPos & mask).toInt))
            rd.lazySet(rdPos + 1)
            Some(item)
        }
    }

    def remove(): SequencedItem = poll() match {
        case None => throw new BufferEmptyException
        case Some(entry) => entry
    }

    /**
     * Rewind the ring buffer to the specified position, if it is not beyond
     * the capacity of the ring buffer.
     */
    def rewind(pos: Long): Boolean = {
        if (pos < 0 || pos > rd.get())
            false
        else if ((wrIntent.get() - pos) > capacity)
            false
        else {
            // Note: in tight cases, the following may cause 'offer'
            // return 'buffer full', but it allows us to avoid a
            // read-write lock.
            val rdCur = rd.get()
            rd.set(pos)
            if ((wrIntent.get() - pos) > capacity) {
                // rollback
                rd.set(rdCur)
                false
            }else {
                true
            }
        }
    }

    def seek(pos: Long): Unit =
        if (!rewind(pos)) throw new NotInBufferException

    /**
     * Check if the ring buffer is empty
     */
    def isEmpty: Boolean = wr.get() == rd.get()

    /**
     * Check if the ring buffer is full
     */
    def isFull: Boolean = size >= capacity

    /**
     * Get the number of elements in the ring buffer
     */
    def size: Int = {
        var rdPos = rd.get()
        var wrPos = wr.get()
        while (rdPos != rd.get()) {
            rdPos = rd.get()
            wrPos = wr.get()
        }
        (wrPos - rdPos).toInt
    }

    /**
     * Get the sequence number for the following item
     */
    def curSeqno: Long = rd.get()
}
