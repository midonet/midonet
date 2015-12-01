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

import java.util.{Objects, UUID}

import org.midonet.cluster.data.{ZoomClass, ZoomField, ZoomObject}
import org.midonet.cluster.models.Topology
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.packets.IPAddr

@ZoomClass(clazz = classOf[Topology.Host])
class Host extends ZoomObject with Device {

    def this(host: Host) = {
        this()
        id = host.id
        portIds = host.portIds
        tunnelZoneIds = host.tunnelZoneIds
        tunnelZones = host.tunnelZones
        alive = host.alive
    }

    def this(hostId: UUID, alive: Boolean, portBindings: Map[UUID, String],
             tunnelZones: Map[UUID, IPAddr]) = {
        this()
        id = hostId
        this.portIds = portBindings.keySet
        this.portBindings = portBindings
        this.tunnelZoneIds = tunnelZones.keySet
        this.tunnelZones = tunnelZones
        this.alive = alive
    }

    @ZoomField(name = "id")
    var id: UUID = _
    @ZoomField(name = "port_ids")
    var portIds = Set.empty[UUID]
    @ZoomField(name = "tunnel_zone_ids")
    var tunnelZoneIds = Set.empty[UUID]

    // To be filled by the HostMapper.
    // The IP address of the host in each one of the tunnel zones.
    var tunnelZones = Map.empty[UUID, IPAddr]

    // To be filled by the HostMapper.
    // The interface to which each port is bound.
    var portBindings = Map.empty[UUID, String]

    // The alive status of the host is stored outside of the host proto.
    var alive: Boolean = false

    override def toString =
        s"Host [id=$id alive=$alive " +
        s"boundPortIds=$portIds portBindings=$portBindings " +
        s"tunnelZoneIds=$tunnelZoneIds tunnelZones=$tunnelZones]"

    override def equals(o: Any): Boolean = o match {
        case host: Host =>
            host.alive == alive &&
            host.id == id &&
            host.portIds == portIds &&
            host.portBindings == portBindings &&
            host.tunnelZoneIds == tunnelZoneIds &&
            host.tunnelZones == tunnelZones
        case _ => false
    }

    override def hashCode: Int =
        Objects.hash(id, portIds, portBindings,
                     tunnelZoneIds, tunnelZones)
}
