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

import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.storage.{StateTable, StateTableStorage}
import org.midonet.midolman.state.{MacToIp4ReplicatedMap, Ip4ToMacReplicatedMap, MacPortMap, PathBuilder}
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.packets.util.AddressConversions._

/** Contains operations related to state tables and legacy replicated maps. */
trait StateTableManager {
    protected def pathBldr: PathBuilder
    protected def stateTableStorage: StateTableStorage

    protected def getBridgeMacPortsPath(bridgeId: UUID) =
        pathBldr.getBridgeMacPortsPath(bridgeId)

    protected def getBridgeVlansPath(bridgeId: UUID) =
        pathBldr.getBridgeVlansPath(bridgeId)

    protected def getBridgeArpTable(bridgeId: UUID): StateTable[IPv4Addr, MAC] =
        stateTableStorage.bridgeArpTable(bridgeId)

    protected def arpEntryPath(nwId: UUID, ipAddr: String, mac: String) = {
        val tablePath = stateTableStorage.bridgeArpTablePath(nwId)
        val entry = Ip4ToMacReplicatedMap.encodePersistentPath(ipAddr, mac)
        tablePath + entry
    }

    protected def getPortPeeringTable(portId: UUID): StateTable[MAC, IPv4Addr] =
        stateTableStorage.routerPortPeeringTable(portId)

    protected def portPeeringEntryPath(portId: UUID, mac: String,
                                       ipAddr: String): String = {
        val tablePath = stateTableStorage.routerPortPeeringTablePath(portId)
        val entry = MacToIp4ReplicatedMap.encodePersistentPath(mac, ipAddr)
        tablePath + entry
    }

    protected def macEntryPath(nwId: UUID, mac: String, portId: UUID) = {
        val entry = MacPortMap.encodePersistentPath(MAC.fromString(mac), portId)
        pathBldr.getBridgeMacPortEntryPath(nwId, Bridge.UNTAGGED_VLAN_ID, entry)
    }
}