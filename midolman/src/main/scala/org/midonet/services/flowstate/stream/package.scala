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
import java.nio.file.{Paths, StandardOpenOption}
import java.util.UUID

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.util.collection.RingBuffer
import org.midonet.util.io.stream._

package object stream {

    val LengthSize = 4

    val log = Logger(
        LoggerFactory.getLogger("org.midonet.services.stream.flowstate-stream"))

    /**
      * Output/Input stream builders for a given port.
      */
    object ByteBufferBlockWriter {

        def apply(config: FlowStateConfig,
                  portId: UUID): ByteBufferBlockWriter[BlockHeader] = {
            ???
        }
    }

    object ByteBufferBlockReader {

        @throws[IOException]
        def apply(config: FlowStateConfig,
                  portID: UUID): ByteBufferBlockReader[TimedBlockHeader] = {
            val fileName = System.getProperty("midolman.log.dir") +
                           s"${config.logDirectory}/$portID"
            val filePath = Paths.get(fileName)
            val fileChannel = FileChannel.open(filePath, StandardOpenOption.READ)

            // create factory for this file
            val blockFactory = new MemoryMappedBlockFactory(fileChannel,
                                                            FileChannel.MapMode.READ_ONLY,
                                                            config.blockSize,
                                                            FlowStateBlock)
            val pool = new RingBuffer[ByteBuffer](config.blocksPerPort, null)

            // Need to read from file

            // Read header to know where is the first block

            new ByteBufferBlockReader[TimedBlockHeader](FlowStateBlock, pool)
        }
    }

}

