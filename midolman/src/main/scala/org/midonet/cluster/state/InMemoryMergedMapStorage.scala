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

package org.midonet.cluster.state

import java.util.UUID

import scala.collection.concurrent.TrieMap

import org.midonet.cluster.data.storage.{InMemoryMergedMapBus, MergedMap}
import org.midonet.cluster.state.MergedMapStateStorage.{PortOrdering, PortTS}
import org.midonet.packets.MAC

class InMemoryMergedMapStorage extends MergedMapState {

    private val maps = new TrieMap[(UUID, Short), MergedMap[_, _]]

    /**
     * Returns true iff merged maps are enabled.
     */
    override def isEnabled = true

    /**
     * Gets the MAC-port merged map for the specified bridge.
     */
    override def bridgeMacMergedMap(bridgeId: UUID,
        vlanId: Short): MergedMap[MAC, PortTS] = {

        maps.getOrElseUpdate((bridgeId, vlanId), {
            val mapId = "MacTable-" + vlanId + "-" + bridgeId.toString
            val inMemBus = new InMemoryMergedMapBus[MAC, PortTS](mapId, "owner")
            val map = new MergedMap[MAC, PortTS](inMemBus)(new PortOrdering())
            maps.putIfAbsent((bridgeId, vlanId), map)
            maps((bridgeId, vlanId))
        }).asInstanceOf[MergedMap[MAC, PortTS]]
    }
}
