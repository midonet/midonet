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

import org.midonet.cluster.cli.commands.objects.Host.PortBindingConverter
import org.midonet.cluster.data.{ZoomField, ZoomObject}
import org.midonet.cluster.models.Topology.Host.PortToInterface
import org.midonet.cluster.util.MapConverter
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter, _}
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddressConverter}
import org.midonet.packets.IPAddr

object Host {
    class PortBindingConverter extends MapConverter[UUID, String, PortToInterface] {
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

/**
 * A topology host.
 */
final class Host extends ZoomObject {
    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    var id: UUID = _
    @ZoomField(name = "name")
    var name: String = _
    @ZoomField(name = "addresses", converter = classOf[IPAddressConverter])
    var addresses: Set[IPAddr] = _
    @ZoomField(name = "port_interface_mapping",
               converter = classOf[PortBindingConverter])
    var portBindings: Map[UUID, String] = _
    @ZoomField(name = "tunnel_zone_ids", converter = classOf[UUIDConverter])
    var tunnelZoneIds: Set[UUID] = _
    @ZoomField(name = "flooding_proxy_weight")
    var floodingProxyWeight: Int = _

    override def toString =
        s"id=$id name=$name floodingProxyWeight=$floodingProxyWeight " +
        s"addresses=$addresses bindings=$portBindings tunnelZones=$tunnelZoneIds"
}
