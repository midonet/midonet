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

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.UUID

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.util.collection.RingBufferWithFactory
import org.midonet.util.io.stream._

package object stream {

    val LengthSize = 4

    val log = Logger(
        LoggerFactory.getLogger("org.midonet.services.stream.flowstate-stream"))

    def loadBuffersFromFile(config: FlowStateConfig,
                            fileChannel: FileChannel,
                            factory: BlockFactory[TimedBlockHeader])
    : RingBufferWithFactory[ByteBuffer] = {
            // Find the first and last block in the file
            var (firstBlock, firstTimestamp) = (Int.MaxValue, Long.MaxValue)
            var (lastBlock, lastTimestamp) = (0, 0L)
            for (blockIdx <- 0 until config.blocksPerPort) {
                val bb = factory.allocate(blockIdx)
                val header = FlowStateBlock(factory.allocate(blockIdx))
                if (header.lastEntryTime != -1) {
                    // This block is valid
                    if (header.lastEntryTime < firstTimestamp) {
                        firstTimestamp = header.lastEntryTime
                        firstBlock = blockIdx
                    } else if (header.lastEntryTime > lastTimestamp) {
                        lastTimestamp = header.lastEntryTime
                        lastBlock = blockIdx
                    }
                }
            }
            new RingBufferWithFactory[ByteBuffer](config.blocksPerPort,
                                                  null, factory.allocate,
                                                  lastBlock, firstBlock)
    }

    /**
      * Output/Input stream builders for a given port.
      */
    object ByteBufferBlockWriter {

        def apply(config: FlowStateConfig,
                  portId: UUID): ByteBufferBlockWriter[TimedBlockHeader] = {
            val fileName = System.getProperty("midolman.log.dir") +
                           s"${config.logDirectory}/$portId"
            val filePath = Paths.get(fileName)
            var buffers: RingBufferWithFactory[ByteBuffer] = null
            var factory: BlockFactory[TimedBlockHeader] = null
            if (Files.exists(filePath)) {
                // open for reading and writting
                val fileChannel = FileChannel.open(filePath,
                                                   StandardOpenOption.READ,
                                                   StandardOpenOption.WRITE)
                factory = new MemoryMappedBlockFactory[TimedBlockHeader](
                    fileChannel, FileChannel.MapMode.READ_WRITE, config.blockSize,
                    FlowStateBlock)
                buffers = loadBuffersFromFile(config, fileChannel, factory)
            } else {
                // create file and open for writting
                val fileChannel = FileChannel.open(filePath,
                                                  StandardOpenOption.CREATE,
                                                  StandardOpenOption.WRITE)
                factory = new MemoryMappedBlockFactory[TimedBlockHeader](
                    fileChannel, FileChannel.MapMode.READ_WRITE, config.blockSize,
                    FlowStateBlock)
                buffers = new RingBufferWithFactory[ByteBuffer](
                    config.blocksPerPort, null, factory.allocate)
            }
            new ByteBufferBlockWriter[TimedBlockHeader](
                FlowStateBlock, buffers, config.expirationTime toNanos)
        }
    }

    object ByteBufferBlockReader {

        @throws[IOException]
        def apply(config: FlowStateConfig, portId: UUID)
        : ByteBufferBlockReader[TimedBlockHeader] = {
            val fileName = System.getProperty("midolman.log.dir") +
                           s"${config.logDirectory}/$portId"
            val filePath = Paths.get(fileName)
            if (Files.exists(filePath)) {
                // open for reading
                val fileChannel = FileChannel.open(filePath,
                                                   StandardOpenOption.READ)
                val factory = new MemoryMappedBlockFactory[TimedBlockHeader](
                    fileChannel,
                    FileChannel.MapMode.READ_ONLY,
                    config.blockSize,
                    FlowStateBlock)
                val buffers = loadBuffersFromFile(config, fileChannel, factory)
                new ByteBufferBlockReader[TimedBlockHeader](FlowStateBlock, buffers)
            } else {
                throw new IOException(s"No flow state for port ID $portId in " +
                                      s"$fileName")
            }
        }

    }

}

