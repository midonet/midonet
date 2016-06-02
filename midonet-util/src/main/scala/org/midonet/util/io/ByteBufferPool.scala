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

package org.midonet.util.io

import java.io.{RandomAccessFile, File, EOFException}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

import scala.collection.mutable

trait ByteBufferFactory {

    var currentSize: Int = 0

    val blockSize: Int

    val maxSize: Int

    def allocate(): ByteBuffer = {
        if (currentSize + blockSize < maxSize) {
            currentSize += blockSize
            allocateByteBuffer()
        } else {
            throw new EOFException()
        }
    }

    protected def allocateByteBuffer(): ByteBuffer

}

class InMemoryByteBufferFactory(override val blockSize: Int,
                                override val maxSize: Int) extends ByteBufferFactory {

    def allocateByteBuffer(): ByteBuffer = {
        ByteBuffer.allocate(blockSize)
    }

}

class MemoryMappedFileByteBufferFactory(file: File,
                                        override val blockSize: Int,
                                        override val maxSize: Int) extends ByteBufferFactory {

    val log = new RandomAccessFile(file, "rw")

    def allocateByteBuffer(): ByteBuffer = {
        log.getChannel.map(FileChannel.MapMode.READ_WRITE,
                           currentSize,
                           blockSize)
    }

}

/**
  * Pool of byte buffers so they can be resused without being garbage collected.
  * IMPORTANT: This class is not thread-safe.
  *
  * @param blockSize Size of the underlying bytebuffers in bytes
  * @param maxBuffers Maximum number of buffers to keep in the pool
  */
class ByteBufferPool(val blockSize: Int, val maxBuffers: Int, bbFactory: ByteBufferFactory) {

    var inuse = 0

    val pool = mutable.Queue.empty[ByteBuffer]

    /**
      * Returns a free [[ByteBuffer]] from the pool.
      *
      * @throws [[IndexOutOfBoundsException]] if trying to acquire more byte
      *         buffers than maxBuffers.
      * @return [[ByteBuffer]]
      */
    def acquire(): ByteBuffer = {
        if (pool.isEmpty && inuse == maxBuffers) {
            throw new IndexOutOfBoundsException()
        }

        if (pool.isEmpty) {
            pool.enqueue(bbFactory.allocate())
        }
        inuse += 1
        pool.dequeue()
    }

    /**
      * Returns the ByteBuffer to the pool of unused buffers.
      *
      * @param bb
      */
    def release(bb: ByteBuffer): Unit = {
        inuse -= 1
        bb.clear()
        pool.enqueue(bb)
    }
}
