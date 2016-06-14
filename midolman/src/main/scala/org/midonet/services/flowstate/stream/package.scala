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

import java.nio.ByteBuffer
import java.util.UUID

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.util.collection.ObjectPool
import org.midonet.util.io.stream.{ByteBufferBlockInputStream, ByteBufferBlockOutputStream}

package object stream {

    val LengthSize = 4

    /**
      * Factory object for in memory byte buffers.
      */
    object HeapBlockFactory {

        def allocate(pool: ObjectPool[ByteBuffer], size: Int, portId: UUID): ByteBuffer = {
            ByteBuffer.allocate(size)
        }

        def deallocate(buffer: ByteBuffer, portId: UUID) = {}

    }

    /**
      * Factory object for memory mapped file byte buffers.
      */
    object MemoryMappedBlockFactory {

        def allocate(pool: ObjectPool[ByteBuffer], size: Int, portId: UUID): ByteBuffer = {
            ???
        }

        def deallocate(buffer: ByteBuffer, portId: UUID) = {}

    }

    /**
      * Output/Input stream builders for a given port.
      */
    object ByteBufferBlockOutputStream {

        def apply(config: FlowStateConfig,
                  portId: UUID): ByteBufferBlockOutputStream = {
            ???
        }
    }

    object ByteBufferBlockInputOutputStream {

        def apply(config: FlowStateConfig,
                  portID: UUID): ByteBufferBlockInputStream = {
            ???
        }

    }

}

