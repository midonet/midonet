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

package org.midonet.midolman.topology.devices

import java.util.UUID

import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.packets.IPAddr
import org.midonet.quagga.BgpdConfiguration.{Network, Neighbor}

case class BgpRouter(as: Int,
                     neighbors: Map[IPAddr, BgpNeighbor],
                     networks: Set[Network]) extends Device

case class BgpNeighbor(id: UUID, neighbor: Neighbor) {
    def address = neighbor.address
}
