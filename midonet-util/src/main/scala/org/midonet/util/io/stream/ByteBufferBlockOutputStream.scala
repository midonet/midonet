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

import java.io.{IOException, OutputStream}
import java.nio.ByteBuffer

import scala.collection.mutable

import org.midonet.util.collection.ObjectPool

/**
  * This class implements an output stream for writing arbitrary data on an
  * array of byte buffers (blocks). It supports arbitrary header formats as
  * exposed in [[ByteBufferBlockStream]]. This block writer takes care of
  * allocating a new block from the pool when necessary.
  *
  * @param blockBuilder The builder/reader for the block header.
  * @param buffers The in-order queue of buffers used by the writer.
  * @param bufferPool The pool where the buffer blocks were taken.
  * @param allowSplit if true, an array won't be split in two different
  *                   blocks but allocate a new one and write the whole
  *                   array there. False by default.
  */

class ByteBufferBlockOutputStream[H <: BlockHeader]
                        (override val blockBuilder: BlockHeaderBuilder[H],
                         override val buffers: mutable.Queue[ByteBuffer],
                         override val bufferPool: ObjectPool[ByteBuffer],
                         val allowSplit: Boolean = false)
    extends OutputStream with ByteBufferBlockStream[H] {

    /**
      * Writes the specified byte to this output stream. It allocates a new
      * byte buffer if there's no space left on the current block.
      *
      * @param b
      */
    override def write(b: Int): Unit = {
        if (checkRemainingBuffer >= 1) {
            current.put(b.toByte)
        }
    }

    /**
      * Writes <code>len</code> bytes from the specified byte array
      * starting at offset <code>off</code> to this output stream. It takes
      * care of allocating a new block if needed until the requested amount
      * of bytes is written. If member allowSplit is true, a chunk can be
      * split between two or more blocks for a more space friendly storage.
      */
    @throws[IOException]
    override def write(buff: Array[Byte], offset: Int, length: Int): Unit = {
        if (allowSplit) {
            var written = 0

            @inline def remainingLength = length - written

            while (remainingLength > 0) {
                val remainingBuffer = checkRemainingBuffer()
                val toWrite = if (remainingBuffer < remainingLength) {
                    remainingBuffer
                } else {
                    remainingLength
                }
                log.debug(s"Writing $toWrite bytes to block ${buffers.length}")
                current.put(buff, written, toWrite)
                written += toWrite
                blockBuilder.update(current)
            }
        } else {
            val remainingBuffer = checkRemainingBuffer()
            if (length > current.capacity) {
                log.error(s"Buffer size ($length bytes) bigger than block size " +
                          s"${current.capacity} but splitting it is not allowed. " +
                          s"This is likely a configuration issue. Try increasing " +
                          s"the block size.")
                throw new IOException()
            }
            if (length > remainingBuffer) {
                allocateNewBlock()
            }
            current.put(buff, 0, length)
            blockBuilder.update(current)
        }
    }

    @inline private def current = buffers.last

    /** Allocates a new block from buffer pool and updates the output stream. */
    @inline private def allocateNewBlock() = {
        val buffer = bufferPool.take
        blockBuilder.init(buffer)
        buffers += buffer
    }

    /**
      * This method checks the size of the current buffer and returns the amount
      * of remaining bytes that can be written. If there are no buffers, a new
      * block is allocated for immediate use.
      *
      * @return The amount of bytes that can be written to this buffer
      */
    private def checkRemainingBuffer(): Int = {
        if (buffers.isEmpty || buffers.last.remaining() == 0) {
            log.debug("Adding a new block because there was none or the buffer " +
                      "filled up.")
            allocateNewBlock()
        }
        buffers.last.remaining()
    }

}
