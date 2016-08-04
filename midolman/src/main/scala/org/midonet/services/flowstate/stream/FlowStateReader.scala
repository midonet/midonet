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

import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.UUID

import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting

import org.midonet.packets.SbeEncoder
import org.midonet.services.flowstate.stream.snappy.SnappyBlockReader
import org.midonet.util.Clearable
import org.midonet.util.collection.RingBuffer
import org.midonet.util.io.stream._

object FlowStateReader {

    @VisibleForTesting
    private[flowstate] def apply(context: Context,
                                 buffers: RingBuffer[ByteBuffer])
    : FlowStateReader = {
         val blockInput = new ByteBufferBlockReader(FlowStateBlock, buffers)
         val compressedInput = new SnappyBlockReader(blockInput)
         new FlowStateReaderImpl(compressedInput)
    }

    private[flowstate] def apply(context: Context, portId: UUID): FlowStateReader = {
        if (context.ioManager.exists(portId)) {
            val blockReader = ByteBufferBlockReader(context, portId)
            val snappyReader = new SnappyBlockReader(blockReader)
            new FlowStateReaderImpl(snappyReader)
        } else {
            Log warn s"Flow state file for port $portId does not exist."
            throw new FileNotFoundException
        }
    }
}

/**
  * Input stream that decodes flow state messages from a compressed snappy
  * stream. Each port should have its own input stream. Use the
  * [[FlowStateReader#apply]] constructors to create an input stream for a
  * given port.
  */
trait FlowStateReader extends Clearable {
    /**
      * Read the flow state messages from a given stream of flow state messages.
      * It decodes the message and returns the encoder used. The encoder already
      * contains the methods to read the contents of the message.
      *
      * @return The [[SbeEncoder]] The encoder used to decode the message
      */
    def read(): Option[SbeEncoder]

    /**
      * Repositions this stream to the initial position of the underlying
      * storage and leaves it ready to start reading from it again.
      */
    def reset(): Unit

}

protected[flowstate] class FlowStateReaderImpl(var in: SnappyBlockReader)
    extends FlowStateReader {

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
                None // EOF as no more bytes were read from underlying stream
            }
        } catch {
            case NonFatal(e) =>
                log.debug("Unexpected failure reading size of flow state " +
                          "message. It was possibly truncated during a write " +
                          "failure. Ignoring and returning EOF. ")
                None // EOF
        }
    }

    def reset(): Unit = {
        // Snappy input stream does not support reset, allocate a new stream
        in.reset()
        in = new SnappyBlockReader(in.input)
    }

    /**
      * Clears any resource used by the underlying block .
      */
    override def clear(): Unit = {
        in.clear()
    }

}
