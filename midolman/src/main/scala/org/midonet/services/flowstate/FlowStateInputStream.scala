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

import org.apache.zookeeper.server.ByteBufferInputStream
import org.xerial.snappy.SnappyInputStream

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.packets.SbeEncoder

trait FlowStateInputStream {

    /**
      * Read the flow state messages from a given stream of flow state messages.
      * It decodes the message and returns the encoder used. The encoder already
      * contains the methods to read the contents of the message.
      *
      * @throws IOException
      * @return The [[SbeEncoder]] The encoder used to decode the message
      */
    @throws[IOException]
    def readAndDecode(): SbeEncoder

    /**
      * Read the next flow state message from a given stream of messages. This
      * method does not decode the message and returns the raw data in a byte
      * array.
      *
      * @throws IOException If the input stream has been closed.
      * @return The [[ByteBuffer]] into which the data is read
      */
    @throws[IOException]
    def read(): ByteBuffer

}

class SnappyFlowStateInputStream(config: FlowStateConfig, buffers: Seq[ByteBuffer])
    extends FlowStateInputStream {

    var currentBlock = 0

    var in: SnappyInputStream = _

    /**
      * Read the flow state messages from a given stream of flow state messages.
      * It decodes the message and returns the encoder used. The encoder already
      * contains the methods to read the contents of the message.
      *
      * @throws IOException
      * @return The [[SbeEncoder]] The encoder used to decode the message
      */
    override def readAndDecode(): SbeEncoder = {
        // Read a big chunk of flow state (maximum size is MTU-88 bytes)
        // Return the amount of bytes defined in the header
        new SnappyInputStream(new ByteBufferInputStream(buffers.head))
        ???
    }

    /**
      * Read the next flow state message from a given stream of messages. This
      * method does not decode the message and returns the raw data in a byte
      * array.
      *
      * @throws IOException If the input stream has been closed.
      * @return The [[ByteBuffer]] into which the data is read
      */
    override def read(): ByteBuffer = {
        ???
    }
}
