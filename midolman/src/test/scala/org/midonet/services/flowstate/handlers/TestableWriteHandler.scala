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

import java.nio.ByteBuffer
import java.util.UUID

import com.datastax.driver.core.Session

import org.mockito.Mockito._

import org.midonet.cluster.storage.FlowStateStorage
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.NatState.NatKeyStore
import org.midonet.services.flowstate.stream
import org.midonet.services.flowstate.stream.FlowStateWriter

class TestableWriteHandler (context: stream.Context)
    extends FlowStateWriteHandler(context, mock(classOf[Session])) {

    var legacyWrites = 0
    var localWrites = 0

    override def getLegacyStorage = {
        Some(mock(classOf[FlowStateStorage[ConnTrackKeyStore, NatKeyStore]]))
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


    override def writeInLocalStorage(buffer: ByteBuffer): Boolean = {
        val messageWritten = super.writeInLocalStorage(buffer)
        if (messageWritten) {
            localWrites += 1
        }
        messageWritten
    }


    override def maybeWriteInLegacyStorage(buffer: ByteBuffer): Boolean = {
        val messageWritten = super.maybeWriteInLegacyStorage(buffer)
        if (messageWritten) {
            legacyWrites += 1
        }
        messageWritten
    }

}