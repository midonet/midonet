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

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
  * Trait that specify the information that should be present in the
  * headers for the Block[Output/Input]Stream format. The actual implementation
  * and schema of the header is up to the underlying implementation of the
  * block stream.
  */
trait BlockHeader {

    /** Length in bytes of the data stream in the block. Does not account
      * for the header.
      */
    def blockLength: Int

    /** Wether this block contains a valid header */
    def isValid: Boolean
}


/**
  * These traits specify the information and methods that a header
  * builder / reader must implement. This is also specific to the
  * streaming protocol.
  */
trait BlockHeaderBuilder[T <: BlockHeader] {

    val headerSize: Int

    /**
      * Moves the position of the byte buffer to the position specified by
      * the header if it is valid. Needed to read the current information
      * on the buffer without modifying it. If the header is not valid, this
      * method should initialize the meta data information of the block and move
      * the position of this buffer to the header size to not override the
      * header information.
      *
      * @param buffer
      */
    def init(buffer: ByteBuffer): Unit

    /**
      * This method initializes the meta data information of the block and moves
      * the position of this buffer to the header size to not override the
      * header information.
      *
      * @param buffer
      */
    def reset(buffer: ByteBuffer): Unit

    /**
      * Updates the metadata of the block. Shouldn't modify the buffer position.
      *
      * @param buffer
      */
    def update(buffer: ByteBuffer, params: AnyVal*): Unit

    /**
      * Actual builder method that reads the header from the corresponding
      * offsets from the header without touching the buffer position.
      *
      * @param buffer
      * @return
      */
    def apply(buffer: ByteBuffer): T
}

trait BlockFactory[T <: BlockHeader]{

    /** Size of the byte buffer */
    val blockSize: Int

    /** Block builder */
    val blockBuilder: BlockHeaderBuilder[T]

    /**
      * This method allocates a byte buffer of blockSize bytes. This byte buffer
      * will be used as block in the underlying block storage. Also, this
      * byte buffer should initialize the header of the block (if any) and
      * move the current position of the array to the first writable byte
      * (i.e. after the header).
 *
      * @param index
      * @return
      */
    def allocate(index: Int): ByteBuffer

}

/**
  * Factory object for memory mapped file byte buffers.
  *
  * @param fileChannel File channel where map the byte buffers to.
  * @param blockSize Size of the block to map.
  * @param blockBuilder Header builder and initilizer.
  * @tparam T
  */
class MemoryMappedBlockFactory[T <: BlockHeader](val fileChannel: FileChannel,
                                                 override val blockSize: Int,
                                                 override val blockBuilder: BlockHeaderBuilder[T])
    extends BlockFactory[T] {

    def allocate(index: Int): ByteBuffer = {
        val offset = index * blockSize.toLong
        val bb = fileChannel.map(FileChannel.MapMode.READ_WRITE, offset, blockSize)
        blockBuilder.init(bb)
        bb
    }

}

/**
  * Factory object for READ ONLY memory mapped file byte buffers. This
  * factory is usefull when trying to read the current header on a given
  * buffer without initializing its fields.
  */
class ReadOnlyMemoryMappedBlockFactory[T <: BlockHeader](val fileChannel: FileChannel,
                                                         override val blockSize: Int,
                                                         override val blockBuilder: BlockHeaderBuilder[T])
    extends BlockFactory[T] {

    def allocate(index: Int): ByteBuffer = {
        val offset = index * blockSize.toLong
        fileChannel.map(FileChannel.MapMode.READ_ONLY, offset, blockSize)
    }
}

/**
  * Factory object for heap byte buffers. Usefull for testing.
  */
class HeapBlockFactory[T <: BlockHeader](override val blockSize: Int,
                                         override val blockBuilder: BlockHeaderBuilder[T])
    extends BlockFactory[T] {

    def allocate(index: Int): ByteBuffer = {
        val bb = ByteBuffer.allocate(blockSize)
        blockBuilder.init(bb)
        bb
    }

}

