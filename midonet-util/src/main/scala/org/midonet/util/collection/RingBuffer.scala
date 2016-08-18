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

/**
  * Writes(put) on the head and reads(take/peek) from the tail of the queu.
  */
class RingBuffer[T: Manifest](val capacity: Int, val emptyValue: T) {

    protected val ring = new Array[T](capacity)
    protected var readIndex = 0
    protected var writeIndex = 0

    /**
      * Constructor allowing to create the ring buffer with a given position
      * for reading and writting.
      */
    def this(capacity: Int, emptyValue: T, head: Int, tail: Int) = {
        this(capacity, emptyValue)
        readIndex = head
        writeIndex = tail
    }

    def isEmpty: Boolean = readIndex == writeIndex
    def nonEmpty: Boolean = !isEmpty
    def isFull: Boolean =  ((writeIndex + 1) % capacity) == readIndex
    def length: Int = (writeIndex - readIndex + capacity) % capacity

    /**
      * Writes an element to the head of the queue.
      */
    def put(value: T): Unit = {
        if (isFull) {
            throw new IllegalStateException("buffer is full")
        } else {
            ring(writeIndex) = value
            writeIndex = (writeIndex + 1) % capacity
        }
    }

   /**
      * Reads (without removing) the element at the tail of the queue.
      */
    def peek: Option[T] =
        if (isEmpty) None else Option(ring(readIndex))

    /**
      * Reads (without removing) the element at the head of the queue.
      */
    def head: Option[T] =
        if (isEmpty) None else Option(ring((writeIndex - 1 + capacity) % capacity))

    /**
      * Reads and removes the element at the tail of the queue.
      */
    def take(): Option[T] = {
        if (isEmpty) {
            None
        } else {
            val ret = peek
            ring(readIndex) = emptyValue
            readIndex = (readIndex + 1) % capacity
            ret
        }
    }

    /** This iterator is not thread safe. The behaviour is undefined
      * the underlying ring buffer is modified. */
    def iterator: Iterator[T] = new Iterator[T] {
        private var tailIndex = readIndex
        private val headIndex = writeIndex

        override def hasNext = headIndex != tailIndex

        override def next = {
            if (hasNext) {
                val ret = ring(tailIndex)
                tailIndex = (tailIndex + 1) % capacity
                ret
            } else {
                throw new IndexOutOfBoundsException(
                    "Trying to access a value outside the view")
            }
        }

        override def length = (headIndex - tailIndex + capacity) % capacity

    }
}

class RingBufferWithFactory[T: Manifest](capacity: Int,
                                         emptyValue: T,
                                         factory: (Int) => T )
    extends RingBuffer[T](capacity, emptyValue) {

    def this(capacity: Int, emptyValue: T, factory: (Int) => T, head: Int, tail: Int) = {
        this(capacity, emptyValue, factory)
        readIndex = tail
        writeIndex = head
        // Allocate existing items
        for (i <- 0 until length) {
            val idx = (readIndex + i) % capacity
            ring(idx) = factory(idx)
        }
    }

    /**
      * Writes the element created by the factory method to the head
      * of the queue.
      */
    def allocateAndPut(): T = {
        if (isFull) {
            throw new IllegalArgumentException("buffer is full")
        } else {
            val value = factory(writeIndex)
            ring(writeIndex) = value
            writeIndex = (writeIndex + 1) % capacity
            value
        }
    }
}
