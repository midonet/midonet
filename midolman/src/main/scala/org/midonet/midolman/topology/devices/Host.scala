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
import org.midonet.cluster.models.Topology.Host.PortToInterface
import org.midonet.cluster.util.MapConverter
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter, _}
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.devices.Host.PortInterfaceConverter
import org.midonet.packets.IPAddr

object Host {

    /**
     * This class implements the [[MapConverter]] trait to do the conversion
     * between tuples of type (UUID, String) and PortToInterface messages.
     */
    class PortInterfaceConverter
        extends MapConverter[UUID, String, PortToInterface] {

        override def toKey(proto: PortToInterface): UUID = {
            proto.getPortId.asJava
        }

        override def toValue(proto: PortToInterface): String = {
            proto.getInterfaceName
        }

        override def toProto(key: UUID, value: String): PortToInterface = {
            PortToInterface.newBuilder
                .setPortId(key.asProto)
                .setInterfaceName(value)
                .build()
        }
    }

}

@ZoomClass(clazz = classOf[Topology.Host])
class Host extends ZoomObject with Device {

    def this(hostId: UUID, isHostAlive: Boolean, ports: Map[UUID, String],
             zones: Map[UUID, IPAddr]) = {
        this()
        id = hostId
        portBindings = ports
        tunnelZoneIds = zones.keySet
        tunnelZones = zones
        alive = isHostAlive
    }

    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    var id: UUID = _
    @ZoomField(name = "port_interface_mapping",
               converter = classOf[PortInterfaceConverter])
    var portBindings = Map.empty[UUID, String]
    @ZoomField(name = "tunnel_zone_ids", converter = classOf[UUIDConverter])
    var tunnelZoneIds = Set.empty[UUID]

    // To be filled by the HostMapper.
    // The IP address of the host in each one of the tunnel zones.
    var tunnelZones = Map.empty[UUID, IPAddr]
    // The alive status of the host is stored outside of the host proto.
    var alive: Boolean = false

    override def toString =
        s"Host [id=$id portBindings=$portBindings tunnelZones=$tunnelZoneIds]"

    override def equals(o: Any): Boolean = o match {
        case host: Host =>
            host.alive == alive &&
            host.id == id &&
            host.portBindings == portBindings &&
            host.tunnelZoneIds == tunnelZoneIds &&
            host.tunnelZones == tunnelZones
        case _ => false
    }

    override def hashCode: Int =
        Objects.hashCode(alive, id, portBindings, tunnelZoneIds, tunnelZones)
}
