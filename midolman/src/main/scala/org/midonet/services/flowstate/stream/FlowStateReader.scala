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

import java.util.UUID

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.packets.SbeEncoder

object FlowStateReader {

    def apply(config: FlowStateConfig, portId: UUID): FlowStateReader = {
        ???
    }
}

/**
  * Input stream that decodes flow state messages from a compressed snappy
  * stream. Each port should have its own input stream. Use the
  * [[FlowStateReader#apply]] constructors to create an input stream for a
  * given port.
  */
trait FlowStateReader {

    /**
      * Read the flow state messages from a given stream of flow state messages.
      * It decodes the message and returns the encoder used. The encoder already
      * contains the methods to read the contents of the message.
      *
      * @return The [[SbeEncoder]] The encoder used to decode the message
      */
    def read(): Option[SbeEncoder]

}