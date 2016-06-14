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

import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.packets.SbeEncoder
import org.midonet.services.flowstate.stream.snappy.SnappyBlockReader
import org.midonet.util.collection.RingBuffer
import org.midonet.util.io.stream._

object FlowStateReader {

    @VisibleForTesting
    private[flowstate] def apply(config: FlowStateConfig,
                                 buffers: RingBuffer[ByteBuffer])
    : FlowStateReader = {
         val blockInput = new ByteBufferBlockReader(FlowStateBlock, buffers)
         val compressedInput = new SnappyBlockReader(blockInput)
         new FlowStateReader(compressedInput)
    }

    def apply(config: FlowStateConfig,
              portId: UUID): FlowStateReader = {
        try {
            val blockReader = ByteBufferBlockReader(config, portId)
            val snappyReader = new SnappyBlockReader(blockReader)
            new FlowStateReader(snappyReader)
        } catch {
            case NonFatal(e) =>
                throw new IOException("Failed to open flow state file " +
                                      s"for portId $portId")
        }
    }
}

/**
  * Input stream that decodes flow state messages from the input stream. Each
  * port should have its own input stream. Use the [[FlowStateReader#apply]]
  * constructors to create an input stream for a given port. This class is
  * not thread safe.
  *
  * @param in
  */
protected class FlowStateReader(var in: SnappyBlockReader) {

    /**
      * Read the flow state messages from a given stream of flow state messages.
      * It decodes the message and returns the encoder used. The encoder already
      * contains the methods to read the contents of the message.
      *
      * @throws IOException
      * @return The [[SbeEncoder]] The encoder used to decode the message
      */
    def read(): Option[SbeEncoder] = {
        try {
            val length = new Array[Byte](LengthSize)
            if (in.read(length) != -1) {
                val buff = new Array[Byte](ByteBuffer.wrap(length).getInt)
                in.read(buff)
                val encoder = new SbeEncoder()
                encoder.decodeFrom(buff)
                Option(encoder)
            } else {
                log.debug("Unexpected failure reading size of flow state " +
                          "message. It was possibly truncated during a write " +
                          "failure. Ignoring and returning EOF. ")
                None // EOF
            }
        } catch {
            case NonFatal(e) =>
                None // EOF
        }
    }

    /**
      * Refer to InputStream.reset() for the general contract.
      */
    def reset(): Unit = {
        // Snappy input stream does not support reset, allocate a new stream
        in.reset()
        in = new SnappyBlockReader(in.input)
    }

}
