// Copyright 2013 Midokura Inc.

package org.midonet.util.collection

class RingBuffer[T: Manifest](val capacity: Int, emptyValue: T) {

    private val ring = new Array[T](capacity)
    private var readIndex = 0
    private var writeIndex = 0

    def isEmpty = readIndex == writeIndex
    def isFull =  ((writeIndex + 1) % capacity) == readIndex

    def put(value: T) {
        if (isFull) {
            throw new IllegalArgumentException("buffer is full")
        } else {
            ring(writeIndex) = value
            writeIndex = (writeIndex + 1) % capacity
        }
    }

    def peek: Option[T] = if (isEmpty) None else Option(ring(readIndex))

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
}
