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

    case class SequencedItem[T](seqno: Long, item: T)
}

/**
 * A ring buffer providing absolute sequence numbers for the items emitted,
 * making possible to 'rewind' the read pointer to a previous position, as
 * far as it doesn't exceed the ring buffer capacity.
 * NOTE: it assumes a single producer thread (or, at least,
 * no simultaneous 'offers' from different threads), and a single consumer
 * thread (meaning no simultaneous peek/poll/rewind from different threads).
 *
 * @param minCapacity is the minimum capacity of the ring buffer
 * @tparam T is the type of the elements in the ring buffer
 */
class SpscRwdRingBuffer[T: ClassTag](minCapacity: Int) {
    import SpscRwdRingBuffer._

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

    def add(item: T): Unit = if (!offer(item)) throw new BufferFullException

    /**
     * Get the next element in the ring buffer, if any, without extracting it
     */
    def peek: Option[SequencedItem[T]] = {
        val rdPos = rd.get()
        if (wr.get() == rdPos)
            None
        else
            Some(SequencedItem(rdPos, ring((rdPos & mask).toInt)))
    }

    def element(): SequencedItem[T] =
        peek.getOrElse(throw new NotInBufferException)

    /**
     * Extract the next element from the ring buffer, if any
     */
    def poll(): Option[SequencedItem[T]] = {
        val rdPos = rd.get()
        if (wr.get() == rdPos)
            None
        else {
            val item = SequencedItem(rdPos, ring((rdPos & mask).toInt))
            rd.lazySet(rdPos + 1)
            Some(item)
        }
    }

    def remove(): SequencedItem[T] =
        poll().getOrElse(throw new BufferEmptyException)

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
            // Note: in tight cases, the following may cause both 'rewind' and
            // 'offer' to return 'buffer full' in cases where 'offer' would have
            // succeed with the original read position. This allows us to avoid
            // a read-write lock, and it is an acceptable behavior for our
            // main use case...
            val rdCur = rd.getAndSet(pos)
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
     * Note that this provide a 'best effort' value (nothing prevents
     * the buffer from changing again after the size value is returned)
     * In particular note that, during a failing rewind, the reported
     * size could be greater than the actual buffer capacity.
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
