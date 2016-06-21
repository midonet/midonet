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

import java.util.UUID

import org.midonet.cluster.storage.FlowStateStorageWriter
import org.midonet.midolman.config.FlowStateConfig
import org.midonet.packets.SbeEncoder
import org.mockito.Mockito._

class TestableWriteHandler (config: FlowStateConfig)
    extends FlowStateWriteHandler(config, null) {

    private var legacyStorage: FlowStateStorageWriter = _
    private var writes = 0

    def getStorageProvider = storageProvider
    def getWrites = writes

    override def getLegacyStorage = {
        if (legacyStorage eq null)
            legacyStorage = mock(classOf[FlowStateStorageWriter])
        legacyStorage
    }

    override def writeInLocalStorage(portId: UUID, encoder: SbeEncoder): Unit = {
        super.writeInLocalStorage(portId, encoder)
        writes += 1
    }

}