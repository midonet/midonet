/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.util.io.stream

import java.io.IOException
import java.nio.ByteBuffer

import com.google.common.annotations.VisibleForTesting

import org.midonet.util.collection.RingBufferWithFactory

/**
  * This class implements a block writer for writing arbitrary data on an
  * array of byte buffers (blocks). It supports arbitrary header formats
  * exposed in [[BlockHeader]]. This block writer takes care of
  * allocating a new block from the pool when necessary.
  *
  * WARNING: This is not thread safe. The behaviour is undefined if more
  * than one writer uses the same underlying ring buffer. For flow state
  * local storage, we should only have one writer at a time so it's not a
  * big issue.
  *
  * @tparam H Type of the block header used to write block headers.
  * @param blockBuilder The builder for CRUD ops on the block header.
  * @param buffers The pool where the buffer blocks are written.
  * @param expirationTime Expiration time in nanoseconds, same precision as
  *                       used by [[org.midonet.util.concurrent.NanoClock.DEFAULT]]
  */
class ByteBufferBlockWriter[H <: TimedBlockHeader]
                        (val blockBuilder: BlockHeaderBuilder[H],
                         val buffers: RingBufferWithFactory[ByteBuffer],
                         val expirationTime: Long)
    extends TimedBlockInvalidator[H] {

    /**
      * Writes a specified amount of bytes from the input buffer to the
      * underlying storage. It takes care of allocating a new block if the
      * length that need to be written does not fit on the current buffer.
 *
      * @param buff input byte array with the data to be written.
      * @param offset position on the input array where to start.
      * @param length number of bytes to write from the input array to storage.
      */
    @throws[IOException]
    def write(buff: Array[Byte], offset: Int, length: Int): Unit = {
        var (remainingBuffer, block) = checkRemainingBuffer()
        if (length > block.capacity - blockBuilder.headerSize) {
            throw new IOException(
                s"Buffer size ($length bytes) bigger than block size " +
                s"${block.capacity} but splitting it is not allowed. " +
                s"This is likely a configuration issue. Try increasing " +
                s"the block size.")
        }
        if (length > remainingBuffer) {
            block = allocateNewBlock()
        }
        block.put(buff, offset, length)
        blockBuilder.update(block)
    }

    /**
      * Writes the full array (chunk) to the current block. It takes care
      * of allocating a new block if the length that need to be written
      * does not fit on the current buffer.
 *
      * @param buff
      */
    def write(buff: Array[Byte]): Unit = {
        write(buff, 0, buff.length)
    }

    /**
      * Closes this stream without freeing any resource used and leaves
      * it ready for reading from it. This method flushes any buffered data.
      */
    def close(): Unit = {
        for (buffer <- buffers.iterator) {
            buffer.flip()
        }
    }

    /**
      * Removes from the ring buffer the reference to the byte buffer so it's
      * garbage collected accordingly.
      */
    def clear(): Unit = {
        while (!buffers.isEmpty) {
            buffers.take()
        }
    }

    /** Allocates a new block from buffer pool and updates the output stream. */
    private def allocateNewBlock(): ByteBuffer = {
        buffers.synchronized {
            if (buffers.isFull) {
                Log.warn(
                    "Ring buffer is full. Releasing the older byte buffer as " +
                    "it contains older flow state, potentially stale. This " +
                    "is an indication that the rate of flow state " +
                    "generation is too high for the allocated file. Try " +
                    "increasing the number of blocks allowed per port.")
                buffers.take() match {
                    case Some(bb) => blockBuilder.reset(bb)
                    case None =>
                }
            }
            buffers.allocateAndPut()
        }
    }

    /**
      * This method checks the size of the current buffer and returns the amount
      * of remaining bytes that can be written. If there are no buffers, a new
      * block is allocated for immediate use.
      *
      * @return The amount of bytes that can be written to this buffer
      */
    @VisibleForTesting
    protected def checkRemainingBuffer(): (Int, ByteBuffer) = {
        buffers.head match {
            case Some(value) if value.remaining > 0 =>
                (value.remaining, value)
            case _ =>
                val newBlock = allocateNewBlock()
                (newBlock.remaining, newBlock)
        }
    }
}
