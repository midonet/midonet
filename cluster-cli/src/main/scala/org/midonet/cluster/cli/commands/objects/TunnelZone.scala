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
import org.midonet.cluster.data.{ZoomObject, ZoomField}
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
class TunnelZone extends ZoomObject {

    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    var id: UUID = _
    @ZoomField(name = "name")
    var name: String = _
    @ZoomField(name = "type")
    var zoneType: TunnelZoneType = _
    @ZoomField(name = "hosts", converter = classOf[HostConverter])
    var hosts: Map[UUID, IPAddr] = _

    override def toString = s"id=$id name=$name type=$zoneType hosts=$hosts"

}
