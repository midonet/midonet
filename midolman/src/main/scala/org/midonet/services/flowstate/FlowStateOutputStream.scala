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

package org.midonet.services.flowstate

import java.io.{OutputStream, RandomAccessFile}
import java.nio.MappedByteBuffer
import java.nio.channels.{FileLock, FileChannel}
import java.security.MessageDigest
import java.util.UUID

import org.apache.commons.lang.NotImplementedException
import org.xerial.snappy.Snappy

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.packets.SbeEncoder
import org.midonet.util.concurrent.NanoClock

object FlowStateOutputStream extends MidolmanLogging {

    val MagicNumber: Array[Byte] = MessageDigest
        .getInstance("SHA-1").digest("FlowState".getBytes)

    val MB = 1L * 1024 * 1024

}

/**
  * A FlowStateOutputStream is an output stream for writing flow state messages
  * to a file identified by the [[UUID]] of the port associated to that flow
  * state.
  *
  * @param portId
  */
class FlowStateOutputStream(conf: FlowStateConfig, portId: UUID) extends OutputStream {

    import FlowStateOutputStream._

    final val plain = s"${conf.logDirectory}/$portId.log"
    final val compressed = s"${conf.logDirectory}/$portId.sz"

    private val clock = NanoClock.DEFAULT

    private val maxStorageSize = conf.totalStorageSize

    private val expirationTime = conf.expirationTime.toNanos

    private val blockSize = conf.blockSize

    private val compressionRatio = conf.compressionRatio

    private[flowstate] var zeroEntryTime = clock.tick

    private[flowstate] var blockPointer = 0

    private[flowstate] val txLogFile: RandomAccessFile =
        new RandomAccessFile(plain, "rw")

    private[flowstate] val txLogLock: FileLock = txLogFile.getChannel.lock()

    private[flowstate] val txLogBuffer: MappedByteBuffer = txLogFile.getChannel
        .map(FileChannel.MapMode.READ_WRITE, 0, blockSize * compressionRatio * MB)

    private[flowstate] val txLogCompressedFile: RandomAccessFile =
        new RandomAccessFile(compressed, "rw")

    // TODO: before writting, make sure to find the proper place where to start
    // Use txLog.getChannel.setPosition

    override def close(): Unit = {
        // TODO: flush the remaining bytes into the file
        txLogFile.close()
        txLogCompressedFile.close()
        txLogLock.close()
    }

    override def write(b: Int): Unit = {
        throw new NotImplementedException()
    }

    /**
      * Write the flow state message encoded by the `encoder` into the
      * transaction log file.
      *
      * @param encoder
      */
    @throws[Exception]
    def write(encoder: SbeEncoder): Unit = {
        // TODO: think about how to handle variable block size once compressed
        // when do we start compressing? at 2x? 3x? 4x? the size of the block size?
        if (clock.tick - zeroEntryTime > expirationTime) {
            // Wrap around, flow state too old or max size reached
            log.warn(s"Oldest entries are older than configured value. " +
                      s"Truncating flow state log file for port $portId")
            truncateBuffer()
        } else if (txLogCompressedFile.length() > (maxStorageSize / blockSize) * compressionRatio * MB) {
            log.debug(s"File size for flow state exceeded for port $portId. " +
                      s"Truncating file and overwriting oldest entries.")
            truncateBuffer()
        } else if (txLogBuffer.position + encoder.encodedLength() > compressionRatio * blockSize * MB) {
            log.debug(s"Flow state message block filled, compressing.")
            log.debug(s"${txLogBuffer.position}")
            log.debug(s"${encoder.encodedLength()}")
            log.debug(s"${compressionRatio * blockSize * MB}")
            // No space for another message, compress and truncate
            val initTick = clock.tick
            val txLogCompressedBuffer =
                txLogCompressedFile
                    .getChannel.map(FileChannel.MapMode.READ_WRITE,
                                    blockPointer * blockSize * MB,
                                    blockSize * MB)
            txLogBuffer.flip()

            txLogCompressedBuffer.put(MagicNumber)
            txLogCompressedBuffer.putLong(zeroEntryTime)
            log.debug(s"${txLogCompressedBuffer.position}")
            log.debug(s"${txLogCompressedBuffer.limit}")
            // reset position on the buffer so it starts compressing from the start
            Snappy.compress(txLogBuffer, txLogCompressedBuffer)
            // Position is not updated, only limit.
            log.debug(s"Operation took: ${(clock.tick-initTick)/1000000.0} ms. " +
                      s"Original size: ${txLogBuffer.limit()}. " +
                      s"Compressed size: ${txLogCompressedBuffer.limit()}." +
                      s"Compression ratio: ${txLogBuffer.limit().toFloat /
                                             txLogCompressedBuffer.limit()}x.")
            advanceBuffer()

        }
        // In any case, store the flow state message on the current possition
        // of the memory mapped file buffer.
        log.debug(s"Writting flow state message of size ${encoder.encodedLength()} " +
                  s"on position ${txLogBuffer.position()}")
        txLogBuffer.put(encoder.flowStateBuffer.array(),
                        0,
                        encoder.encodedLength())
    }

    private def truncateBuffer(): Unit = {
        blockPointer = 0
        txLogBuffer.position(0)
        zeroEntryTime = clock.tick
    }

    private def advanceBuffer(): Unit = {
        blockPointer += 1
        txLogBuffer.position(0)
        zeroEntryTime = clock.tick
    }



}
