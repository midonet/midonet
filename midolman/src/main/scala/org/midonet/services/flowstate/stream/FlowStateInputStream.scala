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

import scala.collection.mutable
import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.packets.SbeEncoder
import org.midonet.services.flowstate.stream.snappy.SnappyInputStream
import org.midonet.util.io.stream.ByteBufferBlockInputStream

object FlowStateInputStream {
    def apply(config: FlowStateConfig,
               buffers: mutable.Queue[ByteBuffer]): FlowStateInputStream = {
         val blockInput = new ByteBufferBlockInputStream(
             FlowStateBlock, buffers)
         val compressedInput = new SnappyInputStream(blockInput)
         new FlowStateInputStream(compressedInput)
    }

    def apply(config: FlowStateConfig,
              portId: UUID): FlowStateInputStream = {
        // TODO: read from disk
        // If (file exists) -> build the byte buffers queue
        // else -> fail
        ???
    }
}

/**
  * Input stream that decodes flow state messages from the input stream. Each
  * port should have its own input stream. Use the [[FlowStateInputStream#apply]]
  * constructors to create an input stream for a given port. This class is
  * not thread safe.
  *
  * @param in
  */
protected class FlowStateInputStream(var in: SnappyInputStream) {

    val log = Logger(
        LoggerFactory.getLogger("org.midonet.services.flowstatestream"))

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
                log.debug("Before reading content.")
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
                log.debug(s"Failed to parse data while reading. EOF.", e)
                None // EOF
        }
    }

    /**
      * Refer to InputStream.reset() for the general contract.
      */
    def reset(): Unit = {
        // Snappy input stream does not support reset, allocate a new stream
        in.input.reset()
        in = new SnappyInputStream(in.input)
    }

}
