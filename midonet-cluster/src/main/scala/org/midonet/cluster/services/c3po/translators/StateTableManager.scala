/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.services.c3po.translators

import java.util.UUID

import org.midonet.cluster.data.storage.{StateTable, StateTableStorage}
import org.midonet.packets.{IPv4Addr, MAC}

/** Contains operations related to state tables and legacy replicated maps. */
trait StateTableManager {
    protected def stateTableStorage: StateTableStorage

    protected def getBridgeArpTable(bridgeId: UUID): StateTable[IPv4Addr, MAC] =
        stateTableStorage.bridgeArpTable(bridgeId)

    protected def getPortPeeringTable(portId: UUID): StateTable[MAC, IPv4Addr] =
        stateTableStorage.portPeeringTable(portId)

}