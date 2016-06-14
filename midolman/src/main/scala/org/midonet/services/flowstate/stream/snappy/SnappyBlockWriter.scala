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

package org.midonet.services.flowstate.stream.snappy

import java.nio.ByteBuffer

import org.xerial.snappy.Snappy

import org.midonet.util.io.stream.{ByteBufferBlockWriter, TimedBlockHeader}

/**
  * Compressed output stream. Loosely based on
  * [[org.xerial.snappy.SnappyOutputStream]] but with the following caveats:
  * - We don't write any header at the beginning for verification.
  * - The size and compressed block are written in a single chunk of data into
  *   the underlying output stream.
  * We also follow a similar contract to an OutputStream altough we don't
  * extend that class because we don't support writting just one byte but
  * a chunk (array of bytes).
  *
  * @param out underlying output stream.
  * @param blockSize Size of the uncompressed block.
  */
class SnappyBlockWriter(out: ByteBufferBlockWriter[TimedBlockHeader],
                        blockSize: Int) {

    var pointer: Int = 0

    val compressedSize = new Array[Byte](4)
    val uncompressed = new Array[Byte](blockSize)
    val compressed = new Array[Byte](Snappy.maxCompressedLength(blockSize))

    /**
      * Compress the raw byte array data. If the whole chunk does not fit
      * into the uncompressed buffer, then the current raw data is compressed
      * and a new compressed chunk is created.
      *
      * @param array array data of any type (e.g., byte[], float[], long[], ...)
      * @param offset
      * @param length
      * @see java.io.OutputStream@write(byte[] array, int offset, int length)
      */
    def write(array: Array[Byte], offset: Int, length: Int): Unit = {
        if (length > blockSize - pointer) {
            // Not enough space for the chunk in uncompressed, compress first
            dump()
        }
        Snappy.arrayCopy(array, offset, length, uncompressed, pointer)
        pointer += length
    }

    /**
      * @see java.io.OutputStream#flush()
      */
    def flush(): Unit = {
        dump()
    }

    /**
      * @see java.io.OutputStream#close()
      */
    def close(): Unit = {
        flush()
        out.close()
    }

    protected def dump(): Unit = {
        if (pointer > 0) {
            // Compress and dump the buffer content
            val compressedSize = Snappy.compress(
                uncompressed, 0, pointer, compressed, 4)
            // Write compressed size to compressed buffer
            ByteBuffer.wrap(compressed, 0, 4).putInt(compressedSize)
            out.write(compressed, 0, compressedSize + 4)
            pointer = 0
        }
    }
}
