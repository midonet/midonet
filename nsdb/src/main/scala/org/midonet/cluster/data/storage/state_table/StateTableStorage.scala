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

package org.midonet.cluster.data.storage.state_table

import java.util.UUID

import BridgeArpTableMergedMap.{ArpTableUpdate, MacOrdering, MacTS}
import org.midonet.cluster.data.storage.MergedMap.Update
import RouterArpCacheMergedMap.{ArpCacheUpdate, ArpCacheOrdering, ArpEntryTS}
import org.midonet.cluster.data.storage._
import org.midonet.cluster.data.storage.state_table.MacTableMergedMap.{MacTableUpdate, PortOrdering, PortTS}
import org.midonet.cluster.storage.KafkaConfig
import org.midonet.conf.HostIdGenerator
import org.midonet.packets.{IPv4Addr, MAC}

/**
 * A trait that complements the [[Storage]] trait with support for high
 * performance state tables.
 */
trait StateTableStorage {
    type ArpUpdate = Update[IPv4Addr, MAC]

    /** Gets the MAC-port table for the specified device and owner. */
    def macTable(deviceId: UUID, vlanId: Short, owner: String)
    : StateTable[MAC, UUID, MacTableUpdate]

    /** Returns the IPv4 ARP table for the specified bridge. */
    def bridgeArpTable(bridgeId: UUID): StateTable[IPv4Addr, MAC, ArpTableUpdate]

    /** Returns the IPv4 ARP table for the specified router. */
    def routerArpCache(routerId: UUID)
    : StateTable[IPv4Addr, ArpCacheEntry, ArpCacheUpdate]
}

class MergedMapStateTableStorage(cfg: KafkaConfig,
                                 busBuilder: MergedMapBusBuilder)
    extends StateTableStorage {

    override def macTable(deviceId: UUID, vlanId: Short, ownerId: String)
    : StateTable[MAC, UUID, MacTableUpdate] = {

        val mapId = "MacTable-" + vlanId + "-" + deviceId.toString
        val bus = busBuilder.newBus[MAC, PortTS](mapId, ownerId, TableType.MAC,
                                                 cfg)
        val map = new MergedMap[MAC, PortTS](bus)(new PortOrdering())
        new MacTableMergedMap(deviceId, vlanId, map)
    }

    override def bridgeArpTable(bridgeId: UUID)
    : StateTable[IPv4Addr, MAC, ArpTableUpdate] = {
        val mapId = "ArpTable-" + bridgeId.toString
        val ownerId = HostIdGenerator.getHostId.toString
        val bus = busBuilder.newBus[IPv4Addr, MacTS](mapId, ownerId,
                                                     TableType.ARP_TABLE, cfg)
        val map = new MergedMap[IPv4Addr, MacTS](bus)(new MacOrdering())
        new BridgeArpTableMergedMap(bridgeId, map)
    }

    override def routerArpCache(routerId: UUID)
    : StateTable[IPv4Addr, ArpCacheEntry, ArpCacheUpdate] = {
        val mapId = "ArpTable-" + routerId.toString
        val ownerId = HostIdGenerator.getHostId.toString
        val bus = busBuilder.newBus[IPv4Addr, ArpEntryTS](mapId, ownerId,
                                                          TableType.ARP_CACHE,
                                                          cfg)
        val map = new MergedMap[IPv4Addr, ArpEntryTS](bus)(new ArpCacheOrdering())
        new RouterArpCacheMergedMap(routerId, map)
    }
}