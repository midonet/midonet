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
import org.midonet.services.FlowStateStreamLog
import org.midonet.services.flowstate.stream.snappy.SnappyBlockWriter
import org.midonet.util.Clearable
import org.midonet.util.collection.RingBufferWithFactory
import org.midonet.util.io.stream._
import org.midonet.util.logging.Logging

object FlowStateWriter {

    @VisibleForTesting
    private[flowstate] def apply(context: Context,
                                 buffers: RingBufferWithFactory[ByteBuffer])
    : FlowStateWriter = {
        val blockWriter = new ByteBufferBlockWriter(
            FlowStateBlock, buffers, context.config.expirationTime toNanos)
        val snappyWriter = new SnappyBlockWriter(blockWriter, context.config.blockSize)
        new FlowStateWriterImpl(context.config, snappyWriter)
    }

    @VisibleForTesting
    private[flowstate] def apply(context: Context, portId: UUID): FlowStateWriter = {
        val blockWriter = ByteBufferBlockWriter(context, portId)
        val snappyWriter = new SnappyBlockWriter(blockWriter, context.config.blockSize)
        new FlowStateWriterImpl(context.config, snappyWriter)
    }
}

/**
  * Output stream where flow state is written to already encoded. Each port
  * should have its own output stream. Use the [[FlowStateWriter#apply]]
  * constructors to create an output stream for a given port.
  */
trait FlowStateWriter extends Closeable with Flushable with Clearable {

    /**
      * Write the flow state message encoded by the 'encoder' into the
      * data stream.
      */
    def write(encoder: SbeEncoder): Unit
}

protected[flowstate] class FlowStateWriterImpl(val config: FlowStateConfig,
                                               val out: SnappyBlockWriter)
    extends FlowStateWriter with Logging {

    override def logSource = FlowStateStreamLog
    override def logMark = "FlowStateWriter"

    private[flowstate] val buff =
        ByteBuffer.allocate(LengthSize +
                            FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)

    def write(encoder: SbeEncoder): Unit = {
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
        out.close()
    }

    /**
      * Closes and clears any resource used by the underlying block writer.
      */
    override def clear(): Unit = {
        close()
        out.clear()
    }
}
