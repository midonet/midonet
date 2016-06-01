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

import java.io.InputStream
import java.util.UUID

import org.apache.commons.lang.NotImplementedException

import org.midonet.cluster.flowstate.proto.FlowState

/**
  * A FlowStateInputStream is an input stream that reads flow state messages
  * from the underlying storage system.
  *
  * @param portId
  */
class FlowStateInputStream(portId: UUID) extends InputStream {

    override def read(): Int = {
        throw new NotImplementedException()
    }

    def readFlowStateMessage(): FlowState = ???

    def hasNext: Boolean = ???
}
