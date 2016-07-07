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
package org.midonet.services.flowstate.handlers

import org.midonet.cluster.models.Commons.UUID
import org.midonet.services.flowstate.stream.Context

import scala.util.Random

class TestableReadHandler(context: Context, validPorts: Seq[UUID])
    extends FlowStateReadHandler(context) {

    private var flowStateReads = 0
    private var bufferReads = 0
    private var bufferWrites = 0

    def getFlowStateReads = flowStateReads
    def getBufferReads =  bufferReads
    def getBufferWrites = bufferWrites

    def validPortId = validPorts(Random.nextInt(validPorts.size))

    override def getFlowStateReader(portId: UUID) = {
        flowStateReads += 1
        super.getFlowStateReader(portId)
    }

    override def getByteBufferBlockReader(portId: UUID) = {
        bufferReads += 1
        super.getByteBufferBlockReader(portId)
    }

    override def getByteBufferBlockWriter(portId: UUID) = {
        bufferWrites += 1
        super.getByteBufferBlockWriter(portId)
    }
}
