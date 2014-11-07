/*
 * Copyright 2014 Midokura SARL
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

import org.midonet.cluster.data.{ZoomField, ZoomObject, ZoomClass}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.HostInterfacePort
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddrConverter}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.packets.IPAddr

@ZoomClass(clazz = classOf[Topology.Host])
class Host extends ZoomObject {

    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    var id: UUID = _
    @ZoomField(name = "name")
    var name: String = _
    @ZoomField(name = "alive")
    var alive: Boolean = _
    @ZoomField(name = "addresses", converter = classOf[IPAddrConverter])
    var addresses: Set[IPAddr] = _
    @ZoomField(name = "port_interface_mapping")
    var portInterfaceMapping: Set[HostInterfacePort] = _
    @ZoomField(name = "flooding_proxy_weight")
    var floodingProxyWeight: Int = _
    @ZoomField(name = "tunnel_zone_ids", converter = classOf[UUIDConverter])
    var tunnelZoneIds: Set[UUID] = _

    // To be filled by the HostObservable.
    // A mapping of tunnel zone ids to set of ip addresses.
    var tunnelZones: Map[UUID, Set[IPAddr]] = _
}
