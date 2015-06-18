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
import org.midonet.midolman.state.{Ip4ToMacReplicatedMap, MacPortMap, PathBuilder}
import org.midonet.packets.MAC
import org.midonet.packets.util.AddressConversions._

/** Contains legacy Bridge IP4 Mac Replicated Map related operations. */
trait BridgeStateTableManager {
    protected def pathBldr: PathBuilder

    protected def getBridgeIP4MacMapPath(bridgeId: UUID) =
        pathBldr.getBridgeIP4MacMapPath(bridgeId)

    protected def getBridgeMacPortsPath(bridgeId: UUID) =
        pathBldr.getBridgeMacPortsPath(bridgeId)

    protected def getBridgeVlansPath(bridgeId: UUID) =
        pathBldr.getBridgeVlansPath(bridgeId)

    protected def arpEntryPath(nwId: UUID, ipAddr: String, mac: String) = {
        val entry = Ip4ToMacReplicatedMap.encodePersistentPath(
                ipAddr, MAC.fromString(mac))
        pathBldr.getBridgeIP4MacMapPath(nwId) + entry
    }

    protected def macEntryPath(nwId: UUID, mac: String, portId: UUID) = {
        val entry = MacPortMap.encodePersistentPath(MAC.fromString(mac), portId)
        pathBldr.getBridgeMacPortEntryPath(nwId, Bridge.UNTAGGED_VLAN_ID, entry)
    }
}