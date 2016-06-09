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

import java.io.InputStream
import java.nio.ByteBuffer

import scala.collection.mutable

import org.midonet.util.collection.ObjectPool

/**
  * This class implements an input stream for reading arbitrary data stored
  * on blocks of data writen by the [[ByteBufferBlockOutputStream]]. It
  * supports arbitrary header formats as exposed in [[ByteBufferBlockStream]].
  * This block reader supports reading chunks bigger than the block size.
  *
  * @param blockBuilder The builder/reader for the block header.
  * @param buffers The in-order queue of buffers used by the writer.
  * @param bufferPool The pool where the buffer blocks were taken.
  */
class ByteBufferBlockInputStream[H <: BlockHeader]
                    (override val blockBuilder: BlockHeaderBuilder[H],
                     override val buffers: mutable.Queue[ByteBuffer],
                     override val bufferPool: ObjectPool[ByteBuffer])
    extends InputStream
            with ByteBufferBlockStream[H] {

    var blockIter: Iterator[ByteBuffer] = _
    var current: ByteBuffer = _

    reset()

    /**
      * Reads the next byte from the stream.
      * See [[InputStream]] for the general contract.
      * @return The next byte read cast as an Int or -1 if there were no bytes to read.
      */
    override def read(): Int = {
        log.debug("Reading a single byte")
        if (eof) {
            -1
        } else if (current.remaining() > 0) {
            current.get() & 0xff
        } else {
            nextBlock()
            read()
        }
    }

    /**
      * Reads some number of bytes from the input stream and stores them into
      * the buffer array <code>buff</code>. See [[InputStream]] for the general
      * contract.
      *
      * @param buff
      * @param offset
      * @param length
      * @return
      */
    override def read(buff: Array[Byte], offset: Int, length: Int): Int = {
        var bytesToRead = length

        @inline def currentOffset = {
            offset + (length - bytesToRead)
        }

        if (eof) { -1 }
        else {
            while (bytesToRead > 0 && !eof) {
                if (bytesToRead <= current.remaining) {
                    // enough data in current buffer
                    current.get(buff, currentOffset, bytesToRead)
                    bytesToRead = 0
                } else {
                    // read remaining bytes and advance buffer
                    val readLen = current.remaining()
                    current.get(buff, currentOffset, readLen)
                    bytesToRead -= readLen
                    nextBlock()
                }
            }
            length - bytesToRead
        }
    }

    /**
      * Repositions this stream to the position at the time the
      * <code>mark</code> method was last called on this input stream.
      * See [[InputStream]] for the general contract.
      */
    override def reset(): Unit = {
        blockIter = buffers.iterator
        nextBlock()
    }

    @inline private def eof = {
        current == null ||
        (current.position - blockBuilder.headerSize == blockBuilder(current).blockLength &&
        !hasNextBlock)
    }

    @inline private def hasNextBlock = blockIter.hasNext

    private def nextBlock(): Unit = {
        if (hasNextBlock) {
            current = blockIter.next
            current.position(blockBuilder.headerSize)
        } else {
            log.debug("No more block available for reading.")
        }
    }

}
