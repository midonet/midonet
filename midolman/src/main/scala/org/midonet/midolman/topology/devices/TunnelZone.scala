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
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter, _}
import org.midonet.cluster.util.{IPAddressUtil, MapConverter}
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.devices.TunnelZone.HostIpConverter
import org.midonet.packets.IPAddr

object TunnelZone {

    /**
     * This class implements the MapConverter trait to do the conversion between
     * tuples of type (UUID, IPAddr) and HostToIp protos.
     */
    class HostIpConverter extends MapConverter[UUID, IPAddr, HostToIp] {

        override def toKey(proto: HostToIp): UUID = {
            proto.getHostId.asJava
        }
        def toValue(proto: HostToIp): IPAddr = {
            IPAddressUtil.toIPAddr(proto.getIp)
        }
        def toProto(key: UUID, value: IPAddr): HostToIp = {
            HostToIp.newBuilder
                .setHostId(key.asProto)
                .setIp(IPAddressUtil.toProto(value))
                .build()
        }
    }

}

@ZoomClass(clazz = classOf[Topology.TunnelZone])
class TunnelZone extends ZoomObject with Device {

    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    var id: UUID = _
    @ZoomField(name = "name")
    var name: String = _
    @ZoomField(name = "type")
    var zoneType: TunnelZoneType = _
    @ZoomField(name = "hosts", converter = classOf[HostIpConverter])
    var hosts: Map[UUID, IPAddr] = _

    override def toString =
        s"TunnelZone [id=$id name=$name type=$zoneType hosts=$hosts]"

    override def equals(obj: Any): Boolean = obj match {
        case tunnelZone: TunnelZone =>
            id == tunnelZone.id && name == tunnelZone.name &&
            zoneType == tunnelZone.zoneType && hosts == tunnelZone.hosts

        case _ => false
    }

    override def hashCode: Int = Objects.hashCode(id, name, zoneType, hosts)
}