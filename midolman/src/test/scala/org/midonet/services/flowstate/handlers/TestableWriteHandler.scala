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

import java.util
import java.util.UUID

import org.midonet.cluster.storage.FlowStateStorageWriter
import org.midonet.packets.SbeEncoder
import org.midonet.services.flowstate.stream
import org.midonet.services.flowstate.stream.FlowStateWriter
import org.mockito.Mockito._

class TestableWriteHandler (context: stream.Context)
    extends FlowStateWriteHandler(context, null) {

    private var legacyStorage: FlowStateStorageWriter = _
    private var writes = 0

    def getStorageProvider = storageProvider
    def getWrites = writes

    override def getLegacyStorage = {
        if (legacyStorage eq null)
            legacyStorage = mock(classOf[FlowStateStorageWriter])
        legacyStorage
    }

    override def getFlowStateWriter(portId: UUID): FlowStateWriter =
        portWriters.synchronized {
            if (portWriters.containsKey(portId)) {
                portWriters.get(portId)
            } else {
                val writer = mock(classOf[FlowStateWriter])
                portWriters.put(portId, writer)
                writer
            }
        }


    override def writeInLocalStorage(ingressPortId: UUID,
                                     egressPortIds: util.ArrayList[UUID],
                                     encoder: SbeEncoder): Unit = {
        super.writeInLocalStorage(ingressPortId, egressPortIds, encoder)
        writes += 1
    }

}