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

package org.midonet.services.flowstate.stream

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util.UUID

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.services.flowstate.stream.FlowStateManager.{BlockWriter, Buffers}
import org.midonet.services.flowstate.stream.snappy.SnappyBlockWriter
import org.midonet.util.collection.RingBufferWithFactory
import org.midonet.util.io.stream._

object FlowStateManager {
    type Buffers = RingBufferWithFactory[ByteBuffer]
    type BlockWriter = ByteBufferBlockWriter[TimedBlockHeader]
}

class FlowStateManager(config: FlowStateConfig) {


    protected[flowstate] val buffers = TrieMap.empty[UUID, Buffers]

    /**
      * Mapping between the [[UUID]] of a port and its corresponding
      * [[FlowStateWriter]].
      */
    val stateWriters = TrieMap.empty[UUID, FlowStateWriter]

    /**
      * Mapping between the [[UUID]] of a port and its corresponding
      * [[BlockWriter]].
      */
    val blockWriters = TrieMap.empty[UUID, BlockWriter]

    /**
      * List of writers in the waiting list to be removed. We add them here
      * when we close a given port so the block invalidator knows that it is
      * safe to remove the header (because we are no longer writing to it).
      */
    val writersToRemove = TrieMap.empty[UUID, BlockWriter]

    /**
      * Open memory mapped backed file with read/write access. If the file
      * exists, it loads the current metadata (headers, initial block and
      * final block) from disk and returns its [[Buffers]] representation. If
      * the file does not exist, it is created and an empty [[Buffers]] object
      * is returned. These objects are internally cached so they are reused by
      * upper layers reader and writers.
      *
      * It is safe for multiple FlowStateReader and ByteBufferBlockReaders to
      * to reuse the underlying [[Buffers]] instance.
      *
      * @param portId
      * @return
      */
    def open(portId: UUID): Buffers = {
        buffers.getOrElseUpdate(portId, {
            val filePath = getFileForPort(portId)
            val buffer = if (Files.exists(filePath)) {
                // open for read/write.
                // TODO:
                // the block factories are hardcoded to memory mapped files.
                // Generalize.
                val fileChannel = FileChannel.open(filePath,
                                                   StandardOpenOption.READ,
                                                   StandardOpenOption.WRITE)
                val readFactory =
                    new ReadOnlyMemoryMappedBlockFactory[TimedBlockHeader](
                        fileChannel, config.blockSize, FlowStateBlock)
                val writeFactory =
                    new MemoryMappedBlockFactory[TimedBlockHeader](
                        fileChannel, config.blockSize, FlowStateBlock)
                loadBuffersFromFile(config,
                                    fileChannel,
                                    readFactory,
                                    writeFactory)
            } else {
                val fileChannel = FileChannel.open(filePath,
                                                   StandardOpenOption.CREATE,
                                                   StandardOpenOption.READ,
                                                   StandardOpenOption.WRITE)
                val factory =
                    new MemoryMappedBlockFactory[TimedBlockHeader](
                        fileChannel, config.blockSize, FlowStateBlock)
                new RingBufferWithFactory[ByteBuffer](
                    config.blocksPerPort, null, factory.allocate)
            }
            buffers.put(portId, buffer)
            buffer
        })
    }

    /** Wether the underlying memory mapped file has been created */
    def exists(portId: UUID): Boolean = {
        val filePath = getFileForPort(portId)
        Files.exists(filePath)
    }

    /**
      * Creates a [[FlowStateWriter]] for the given portId or returns a
      * previously cached one.
      *
      * @param portId
      * @return
      */
    def stateWriter(portId: UUID): FlowStateWriter = {
        stateWriters.getOrElseUpdate(portId, {
            val writer = blockWriter(portId)
            val snappyWriter = new SnappyBlockWriter(writer, config.blockSize)
            new FlowStateWriterImpl(config, snappyWriter)
        })
    }

    /**
      * Creates a [[ByteBufferBlockWriter]] for the given portId or returns a
      * previously cache one.
      *
      * @param portId
      * @return
      */
    def blockWriter(portId: UUID): ByteBufferBlockWriter[TimedBlockHeader] = {
        blockWriters.getOrElseUpdate(portId, {
            val ring = open(portId)
            val blockWriter = new BlockWriter(
                FlowStateBlock, ring, config.expirationTime toNanos)
            blockWriter
        })
    }

    /**
      * Closes this portId for writting and prepare it for reading. This needs
      * to be done before starting to send the flow state to a remote request.
      * A subsequent call to stateWriter will return a new instance of a writer
      * over the same port.
      *
      * @param portId
      */
    def close(portId: UUID): Unit = {
        stateWriters.remove(portId) match {
            case Some(writer) =>
                Log debug s"Closing flow state file for port $portId"
                writer.close()
            case _ =>
        }
        blockWriters.remove(portId) match {
            case Some(writer) =>
                Log debug s"Invalidating flow state for port $portId"
                writer.invalidateBlocks(excludeBlocks = 0)
                writersToRemove.put(portId, writer)
            case _ =>
        }
    }

    /**
      * Removes the storage used for the associated portId. Used when the port
      * is removed from the topology.
      *
      * @param portId
      */
    def remove(portId: UUID): Unit = {
        val filePath = getFileForPort(portId)
        try {
            clear(portId)
            Files.delete(filePath)
        } catch {
            case NonFatal(e) =>
                log.debug("Removing a non existing flow state file. Ignoring.")
        }
    }

    /**
      * Load the list of files from storage, remove those not opened. As this
      * is a housekeeping activity, it should not be executed very often.
      */
    def removeInvalid(): Int = {
        var count = 0
        try {
            val existingFiles = Paths.get(storageDirectory)
            Files.list(existingFiles).toArray().foreach { case p: Path =>
                val portId = UUID.fromString(p.getFileName.toString)
                if (!buffers.contains(portId)) {
                    Files.delete(p)
                    count += 1
                }
            }
            count
        } catch {
            case NonFatal(e) =>
                log.warn("Unexpected error while cleaning invalid flow state " +
                         "files. Check that the flow state directory " +
                         s"$storageDirectory exists and is writable by the " +
                         s"MidoNet Agent process.")
                count
        }
    }

    @VisibleForTesting
    private[flowstate] def clear(portId: UUID): Unit = {
        buffers.remove(portId)
        blockWriters.remove(portId)
        writersToRemove.remove(portId)
        stateWriters.remove(portId) match {
            case Some(writer) => writer.clear()
            case _ =>
        }
    }

    @inline
    private[flowstate] def storageDirectory =
        s"${System.getProperty("minions.db.dir")}${config.logDirectory}"

    @inline
    private def getFileForPort(portId: UUID): Path = {
        val fileName = s"$storageDirectory/$portId"
        Paths.get(fileName)
    }

    /**
      * Read blocks from file and initialize the ring buffer on the appropiate
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

}
