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

package org.midonet.cluster.cli.commands.objects

import java.util.UUID

import org.midonet.cluster.cli.commands.objects.TunnelZone.HostConverter
import org.midonet.cluster.data.{ZoomClass, ZoomObject, ZoomField}
import org.midonet.cluster.models.Topology.{TunnelZone => TopologyTunnelZone}
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.util.{IPAddressUtil, MapConverter}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter, _}
import org.midonet.packets.IPAddr

object TunnelZone {
    class HostConverter extends MapConverter[UUID, IPAddr, HostToIp] {
        override def toKey(proto: HostToIp): UUID = {
            proto.getHostId.asJava
        }

        override def toValue(proto: HostToIp): IPAddr = {
            IPAddressUtil.toIPAddr(proto.getIp)
        }

        override def toProto(key: UUID, value: IPAddr): HostToIp = {
            HostToIp.newBuilder
                .setHostId(key.asProto)
                .setIp(IPAddressUtil.toProto(value))
                .build()
        }
    }
}

/**
 *  A topology tunnel zone.
 */
@ZoomClass(clazz = classOf[TopologyTunnelZone])
@CliName(name = "tunnel-zone")
class TunnelZone extends ZoomObject with Obj {
    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    @CliName(name = "id")
    var id: UUID = _
    @ZoomField(name = "name")
    @CliName(name = "name", readonly = false)
    var name: String = _
    @ZoomField(name = "type")
    @CliName(name = "type")
    var zoneType: TunnelZoneType = _
    @ZoomField(name = "hosts", converter = classOf[HostConverter])
    @CliName(name = "hosts")
    var hosts: Map[UUID, IPAddr] = _
}
