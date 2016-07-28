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

import com.codahale.metrics._
import com.codahale.metrics.RatioGauge.Ratio

import org.xerial.snappy.Snappy

import org.midonet.util.Clearable
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
class SnappyBlockWriter(val out: ByteBufferBlockWriter[TimedBlockHeader],
                        blockSize: Int) extends Clearable {

    private class CompressionRatio(compressed: Counter, uncompressed: Counter) extends RatioGauge {

        override def getRatio: Ratio = Ratio.of(uncompressed.getCount,
                                                compressed.getCount)
    }

    private var pointer: Int = 0

    private val compressedSize = new Array[Byte](4)
    private val uncompressed = new Array[Byte](blockSize)
    private val compressed = new Array[Byte](
        Snappy.maxCompressedLength(blockSize + compressedSize.length))
    /*
    private val compressedData: Counter = metrics.counter("compressedData")
    private val uncompressedData: Counter = metrics.counter("uncompressedData")
    private val compressionRatio = metrics.register(
        "compressionRatio",
        new CompressionRatio(compressedData, uncompressedData))
*/
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
        if (length > blockSize) {
            throw new IndexOutOfBoundsException(
                "Length of the array to write bigger than the block size.")
        } else if (length > blockSize - pointer) {
            // Not enough space for the chunk in uncompressed, compress first
            flush()
        }
        Snappy.arrayCopy(array, offset, length, uncompressed, pointer)
        pointer += length
    }

    /**
      * @see java.io.OutputStream#flush()
      */
    def flush(): Unit = {
        if (pointer > 0) {
            // Compress and dump the buffer content
            val compressedSize = Snappy.compress(
                uncompressed, 0, pointer, compressed, 4)
            // Write compressed size to compressed buffer
            ByteBuffer.wrap(compressed, 0, 4).putInt(compressedSize)
            out.write(compressed, 0, compressedSize + 4)
            //compressedData.inc(compressedSize)
            //uncompressedData.inc(pointer)
            pointer = 0
        }
    }

    /**
      * @see java.io.OutputStream#close()
      */
    def close(): Unit = {
        flush()
        out.close()
    }

    /**
      * Clears any resource used by the underlying bytebuffer blok writer.
      */
    override def clear(): Unit = {
        out.clear()
    }

}
