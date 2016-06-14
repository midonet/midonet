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

import java.io._
import java.nio.ByteBuffer
import java.util.UUID

import com.google.common.annotations.VisibleForTesting

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.packets.{FlowStateEthernet, SbeEncoder}
import org.midonet.services.flowstate.stream.snappy.SnappyBlockWriter
import org.midonet.util.Clearable
import org.midonet.util.collection.RingBuffer
import org.midonet.util.io.stream._

object FlowStateWriter {

    @VisibleForTesting
    private[flowstate] def apply(config: FlowStateConfig,
              buffers: RingBuffer[ByteBuffer],
              factory: (Int) => ByteBuffer): FlowStateWriter = {
        new FlowStateWriter(config, buffers, factory)
    }

    def apply(config: FlowStateConfig, portId: UUID): FlowStateWriter = {
        // TODO: load from disk
        // Check if file exists already in storage
        // If (file exists) -> read and load already existing flow state
        // else -> create file, and pass the factory to the pool and an empty queue of buffers
        ???
    }
}

/**
  * Output stream where flow state is written to already encoded. Each port
  * should have its own output stream. Use the [[FlowStateWriter#apply]]
  * constructors to create an output stream for a given port. This class is
  * not thread safe.
  *
  * @param config
  * @param buffers
  */
protected class FlowStateWriter(val config: FlowStateConfig,
                                override val buffers: RingBuffer[ByteBuffer],
                                val factory: (Int) => ByteBuffer)
    extends TimedBlockInvalidator with Closeable with Flushable with Clearable {

    override val expirationTime = config.expirationTime.toNanos

    override val blockBuilder = FlowStateBlock

    private val blockWriter =
        new ByteBufferBlockWriter(blockBuilder, buffers, factory)

    @VisibleForTesting
    private[flowstate] val out: SnappyBlockWriter =
         new SnappyBlockWriter(blockWriter, config.blockSize)

    private[flowstate] val buff =
        ByteBuffer.allocate(LengthSize +
                            FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)

    /**
      * Write the flow state message encoded by the 'encoder' into the
      * data stream.
      */
    def write(encoder: SbeEncoder): Unit = {
        invalidateBlocks()
        val msgSize = encoder.encodedLength()
        buff.clear()
        buff.putInt(msgSize)
        buff.put(encoder.flowStateBuffer.array(), 0, msgSize)
        out.write(buff.array(), 0, LengthSize + msgSize)
    }

    /**
      * Refer to [[Flushable#flush]]
      */
    override def flush(): Unit = out.flush()


    /**
      * Closes this stream without freeing any resource used and leaves
      * it ready for reading from it. This method flushes any buffered data.
      */
    @throws[IllegalStateException]
    override def close(): Unit = {
        log.debug("Closing flow state compressed (snappy) output stream.")
        out.close()
    }
}


