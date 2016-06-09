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

import scala.collection.mutable

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.util.collection.ObjectPool

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
}


/**
  * These traits specify the information and methods that a header
  * builder / reader must implement. This is also specific to the
  * streaming protocol.
  */
trait BlockHeaderBuilder[T <: BlockHeader] {

    val headerSize: Int

    /**
      * Initializes the meta data of the block and moves the position
      * of this buffer to the header size, so subsequent writes to the
      * stream does not override the header information.
      *
      * @param buffer
      */
    def init(buffer: ByteBuffer): Unit

    /**
      * Updates the metadata of the block. Shouldn't modify the buffer position.
      *
      * @param buffer
      */
    def update(buffer: ByteBuffer): Unit

    /**
      * Actual builder method that reads the header from the corresponding
      * offsets from the header without touching the buffer position.
 *
      * @param buffer
      * @return
      */
    def apply(buffer: ByteBuffer): T
}

/**
  * Trait exposing the main information necessary by the underlying in/out
  * block streams.
  */
trait ByteBufferBlockStream[H <: BlockHeader] {

    val log = Logger(
        LoggerFactory.getLogger("org.midonet.services.bytebuffer-blockstream"))

    /** Buffers in use by this stream are stored in this queue */
    val buffers: mutable.Queue[ByteBuffer]

    /** Buffers are obtained from this pool */
    val bufferPool: ObjectPool[ByteBuffer]

    /** Generic block builder for CRUD ops on the header */
    val blockBuilder: BlockHeaderBuilder[H]

}


