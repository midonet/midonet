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

package org.midonet.cluster.data.storage

import java.util.UUID
import javax.annotation.Nonnull

import org.midonet.cluster.data.storage.MacTableMergedMap.{PortOrdering, PortTS}
import org.midonet.cluster.data.storage.MergedMap.Update
import org.midonet.cluster.data.storage.StateTable.MacTableUpdate
import org.midonet.cluster.storage.KafkaConfig
import org.midonet.conf.HostIdGenerator
import org.midonet.packets.{IPv4Addr, MAC}

/**
 * A trait that complements the [[Storage]] trait with support for high
 * performance state tables.
 */
trait StateTableStorage {

    type ArpUpdate = Update[IPv4Addr, MAC]

    /** Gets the MAC-port table for the specified bridge. */
    def bridgeMacTable(bridgeId: UUID, vlanId: Short)
    : StateTable[MAC, UUID, MacTableUpdate]

    /** Returns the IPv4 ARP table for the specified bridge. */
    def bridgeArpTable(bridgeId: UUID): StateTable[IPv4Addr, MAC, ArpUpdate]
}

class MergedMapStateTableStorage(cfg: KafkaConfig,
                                 busBuilder: MergedMapBusBuilder)
    extends StateTableStorage {

    // Returns the arp table of a bridge in the new architecture. Replaces
    // method bridgeMacTable, to be implemented.
    override def bridgeArpTable(bridgeId: UUID)
    : StateTable[IPv4Addr, MAC, ArpUpdate] = ???

    override def bridgeMacTable(bridgeId: UUID, vlanId: Short)
    : StateTable[MAC, UUID, MacTableUpdate] = {

        val mapId = "MacTable-" + vlanId + "-" + bridgeId.toString
        val ownerId = HostIdGenerator.getHostId.toString
        val bus = busBuilder.newBus[MAC, PortTS](mapId, ownerId, TableType.MAC,
                                                 cfg)
        val map = new MergedMap[MAC, PortTS](bus)(new PortOrdering())
        new MacTableMergedMap(bridgeId, vlanId, map)
    }
}