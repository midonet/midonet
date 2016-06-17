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

import java.io.{Closeable, Flushable}
import java.util.UUID

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.packets.SbeEncoder

object FlowStateWriter {

    def apply(config: FlowStateConfig, portId: UUID): FlowStateWriter = {
        ???
    }

}

/**
  * Output stream where flow state is written to already encoded. Each port
  * should have its own output stream. Use the [[FlowStateWriter#apply]]
  * constructors to create an output stream for a given port.
  */
trait FlowStateWriter extends Closeable with Flushable {

    /**
      * Write the flow state message encoded by the 'encoder' into the
      * data stream.
      */
    def write(encoder: SbeEncoder): Unit

}
