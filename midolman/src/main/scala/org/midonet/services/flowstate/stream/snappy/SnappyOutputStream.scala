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

import java.io.OutputStream

import org.xerial.snappy.Snappy

/**
  * Compressed output stream. Loosely based on
  * [[org.xerial.snappy.SnappyOutputStream]] but with the following caveats:
  * - We don't write any header at the beginning for verification.
  * - The size and compressed block are written in a single chunk of data into
  *   the underlying output stream.
  *
  * @param out underlying output stream.
  * @param blockSize Size of the uncompressed block.
  */
class SnappyOutputStream(out: OutputStream,
                         blockSize: Int) extends OutputStream {

    var pointer: Int = 0

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
    override def write(array: Array[Byte], offset: Int, length: Int): Unit = {
        if (length > blockSize - pointer) {
            // Not enough space for the chunk in uncompressed, compress first
            dump()
        }
        Snappy.arrayCopy(array, offset, length, uncompressed, pointer)
        pointer += length
    }

    /**
      * Writes the specified byte to this output stream. The general contract for
      * write is that one byte is written to the output stream. The byte to be
      * written is the eight low-order bits of the argument b. The 24 high-order
      * bits of b are ignored.
      *
      * WARNING: This method will not dump and flush the contents. This should
      * be only used when writing the size of the compressed block, so we ensure
      * that the size and compressed data are written in the same chunk.
      *
      * @see java.io.OutputStream#write(int)
      */
    override def write(b: Int): Unit = {
        throw new NotImplementedError("")
    }

    /**
     * @see java.io.OutputStream#flush()
     */
    override def flush(): Unit = {
        dump()
        out.flush()
    }

    /**
      * @see java.io.OutputStream#close()
      */
    override def close(): Unit = {
        flush()

        super.close()
        out.close()
    }

    protected def dump(): Unit = {
        if (pointer > 0) {
            // Compress and dump the buffer content
            val compressedSize = Snappy.compress(
                uncompressed, 0, pointer, compressed, 4)
            // Write compressed size to compressed buffer
            writeSize(compressedSize)
            // write the compressed buffer + size to the underlying output
            out.write(compressed, 0, compressedSize + 4)
            pointer = 0
        }
    }

    private def writeSize(size: Int): Unit = {
        compressed(0) = (size >> 24 & 0xFF).toByte
        compressed(1) = (size >> 16 & 0xFF).toByte
        compressed(2) = (size >> 8 & 0xFF).toByte
        compressed(3) = (size >> 0 & 0xFF).toByte
    }

}
