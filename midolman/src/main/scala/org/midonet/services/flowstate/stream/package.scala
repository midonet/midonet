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
import java.nio.file.{Path, Files, Paths, StandardOpenOption}
import java.util.UUID

import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.util.collection.RingBufferWithFactory
import org.midonet.util.io.stream._

package object stream {

    val LengthSize = 4

    val log = Logger(
        LoggerFactory.getLogger("org.midonet.services.stream.flowstate-stream"))

    @inline
    private def getFileForPort(config: FlowStateConfig,
                               portId: UUID): Path = {
        val fileName = System.getProperty("minions.db.dir") +
                       s"${config.logDirectory}/$portId"
        Paths.get(fileName)
    }

    def deleteFlowStateFile(config: FlowStateConfig,
                            portId: UUID): Unit = {
        val filePath = getFileForPort(config, portId)
        try {
            Files.delete(filePath)
        } catch {
            case NonFatal(e) =>
                log.debug("Removing a non existing flow state file. Ignoring.")
        }
    }

    /** Read blocks from file and initialize the ring buffer on the appropiate
      * read and write positions, and with the factory passed by parameter.
      */
    private def loadBuffersFromFile(config: FlowStateConfig,
                                    fileChannel: FileChannel,
                                    readFactory: BlockFactory[TimedBlockHeader],
                                    factory: BlockFactory[TimedBlockHeader])
    : RingBufferWithFactory[ByteBuffer] = {
        // Find the first and last block in the file
        var (readIdx, firstTimestamp) = (0, Long.MaxValue)
        var (writeIdx, lastTimestamp) = (0, 0L)
        for (blockIdx <- 0 until config.blocksPerPort) {
            val header = FlowStateBlock(readFactory.allocate(blockIdx))
            if (header.isValid) {
                if (header.lastEntryTime < firstTimestamp) {
                    firstTimestamp = header.lastEntryTime
                    readIdx = blockIdx
                }
                if (header.lastEntryTime > lastTimestamp) {
                    lastTimestamp = header.lastEntryTime
                    writeIdx = (blockIdx + 1) % config.blocksPerPort
                }
            }
        }
        // change mode of the factory to the one initialized
        new RingBufferWithFactory[ByteBuffer](config.blocksPerPort,
                                              null, factory.allocate,
                                              writeIdx, readIdx)
    }

    /**
      * Output/Input stream builders for a given port.
      */
    object ByteBufferBlockWriter {

        def apply(config: FlowStateConfig,
                  portId: UUID): ByteBufferBlockWriter[TimedBlockHeader] = {
            val filePath = getFileForPort(config, portId)
            var buffers: RingBufferWithFactory[ByteBuffer] = null
            var readFactory: BlockFactory[TimedBlockHeader] = null
            var writeFactory: BlockFactory[TimedBlockHeader] = null
            if (Files.exists(filePath)) {
                // open for reading and writting
                val fileChannel = FileChannel.open(filePath,
                                                   StandardOpenOption.READ,
                                                   StandardOpenOption.WRITE)
                readFactory = new ReadOnlyMemoryMappedBlockFactory[TimedBlockHeader](
                    fileChannel, config.blockSize, FlowStateBlock)
                writeFactory = new MemoryMappedBlockFactory[TimedBlockHeader](
                    fileChannel, config.blockSize, FlowStateBlock)
                buffers = loadBuffersFromFile(
                    config, fileChannel, readFactory, writeFactory)
            } else {
                // create file and open for reading and writting
                val fileChannel = FileChannel.open(filePath,
                                                   StandardOpenOption.CREATE,
                                                   StandardOpenOption.READ,
                                                   StandardOpenOption.WRITE)
                writeFactory = new MemoryMappedBlockFactory[TimedBlockHeader](
                    fileChannel, config.blockSize, FlowStateBlock)
                buffers = new RingBufferWithFactory[ByteBuffer](
                    config.blocksPerPort, null, writeFactory.allocate)
            }
            new ByteBufferBlockWriter[TimedBlockHeader](
                FlowStateBlock, buffers, config.expirationTime toNanos)
        }
    }

    object ByteBufferBlockReader {

        @throws[IOException]
        def apply(config: FlowStateConfig, portId: UUID)
        : ByteBufferBlockReader[TimedBlockHeader] = {
            val filePath = getFileForPort(config, portId)
            if (Files.exists(filePath)) {
                // open for reading
                val fileChannel = FileChannel.open(filePath,
                                                   StandardOpenOption.READ,
                                                   StandardOpenOption.WRITE)
                val readFactory = new ReadOnlyMemoryMappedBlockFactory[TimedBlockHeader](
                    fileChannel, config.blockSize, FlowStateBlock)
                val buffers = loadBuffersFromFile(
                    config, fileChannel, readFactory, readFactory)
                new ByteBufferBlockReader[TimedBlockHeader](FlowStateBlock, buffers)
            } else {
                throw new IOException(s"No flow state file for port ID $portId " +
                                      s"in ${filePath.getParent.getFileName}")
            }
        }
    }
}

