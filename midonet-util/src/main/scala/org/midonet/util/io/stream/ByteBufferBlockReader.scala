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

import org.midonet.util.Clearable
import org.midonet.util.collection.RingBuffer

/**
  * This class implements an input stream for reading arbitrary data stored
  * on blocks of data writen by the [[ByteBufferBlockWriter]]. It
  * supports arbitrary header formats as exposed in [[BlockHeader]].
  * This block reader supports reading chunks bigger than the block size. This
  * class extends InputStream so it can be used as the underlying storage
  * for other classes that require InputStream interfaces (e.g. Snappy).
  * The user of this class should use the bulk read method.
  *
  * @tparam H Type of the block header being used to read the block.
  * @param blockBuilder The builder/reader for the block header type.
  */
class ByteBufferBlockReader[H <: BlockHeader]
    (val blockBuilder: BlockHeaderBuilder[H],
     val buffers: RingBuffer[ByteBuffer]) extends InputStream with Clearable {

    private var blockIter: Iterator[ByteBuffer] = _
    private var block: ByteBuffer = _

    reset()

    /**
      * Reads the specified amount of byte from the underlying storage and
      * copies them into the buffer.
      *
      * @param buff Array[Byte] where to store the bytes read.
      * @param offset Position in buff where to start writting.
      * @param length Amount of bytes to read from the underlying storage.
      */
    override def read(buff: Array[Byte], offset: Int, length: Int): Int = {
        var bytesToRead = length

        @inline def currentOffset = {
            offset + (length - bytesToRead)
        }

        @inline def remainingLength = {
            blockBuilder(block).blockLength - (block.position - blockBuilder.headerSize)
        }

        if (eof) { -1 }
        else {
            while (bytesToRead > 0 && !eof) {
                val remaining = remainingLength
                if (bytesToRead <= remaining) {
                    // enough data in current buffer
                    block.get(buff, currentOffset, bytesToRead)
                    bytesToRead = 0
                } else {
                    // read remaining bytes and advance buffer
                    block.get(buff, currentOffset, remaining)
                    bytesToRead -= remaining
                    nextBlock()
                }
            }
            length - bytesToRead
        }
    }

    /**
      * Method not implemented as we only support reading chunks of data through
      * the bulk read method ByteBufferBlockReader.read(array, offset, length).
      *
      * @throws NotImplementedError
      * @return
      */
    @throws[NotImplementedError]
    override def read(): Int = {
        throw new NotImplementedError()
    }

    /**
      * Repositions this stream to the beginning of the stream.
      */
    override def reset(): Unit = {
        blockIter = buffers.iterator
        nextBlock()
    }

    override def clear(): Unit = {
        while (!buffers.isEmpty) {
            buffers.take()
        }
    }

    @inline private def eob = {
        block.position - blockBuilder.headerSize == blockBuilder(block).blockLength
    }

    @inline private def eof = {
        block == null || (eob && !hasNextBlock)
    }

    @inline private def hasNextBlock = blockIter.hasNext

    private def nextBlock(): Unit = {
        if (hasNextBlock) {
            block = blockIter.next
            block.position(blockBuilder.headerSize)
        }
    }
}
