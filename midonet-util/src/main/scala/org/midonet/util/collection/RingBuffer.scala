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

    private val ring = new Array[T](capacity)
    private var readIndex = 0
    private var writeIndex = 0

    def isEmpty: Boolean = readIndex == writeIndex
    def nonEmpty: Boolean = readIndex != writeIndex
    def isFull: Boolean =  ((writeIndex + 1) % capacity) == readIndex
    def length: Int = (writeIndex - readIndex + capacity) % capacity

    /**
      * Writes an element to the head of the queue.
      */
    def put(value: T) {
        if (isFull) {
            throw new IllegalArgumentException("buffer is full")
        } else {
            ring(writeIndex) = value
            writeIndex = (writeIndex + 1) % capacity
        }
    }

    /**
      * Writes the element created by the factory method to the head
      * of the queue.
      */
    def put(factory: (Int) => T): T = {
        val value = factory(writeIndex)
        put(value)
        value
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

    /** This iterator is not thread sage. The behaviour is undefined
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
