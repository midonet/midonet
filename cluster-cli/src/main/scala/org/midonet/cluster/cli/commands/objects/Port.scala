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

import org.midonet.cluster.data.{ZoomConvert, ZoomField, ZoomObject, ZoomClass}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddressConverter}
import org.midonet.cluster.util.IPSubnetUtil.{Converter => IPSubnetConverter}
import org.midonet.cluster.util.MACUtil.{Converter => MACConverter}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.packets.{MAC, IPv4Subnet, IPv4Addr}

/**
 * A topology port.
 */
@ZoomClass(clazz = classOf[Topology.Port], factory = classOf[PortFactory])
@CliName(name = "port")
class Port extends ZoomObject with Obj {
    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    @CliName(name = "id")
    var id: UUID = _
    @ZoomField(name = "admin_state_up")
    @CliName(name = "admin-state-up", readonly = false)
    var adminStateUp: Boolean = true

    @ZoomField(name = "tunnel_key")
    @CliName(name = "tunnel-key", readonly = false)
    var tunnelKey: Long = _
    @ZoomField(name = "inbound_filter_id", converter = classOf[UUIDConverter])
    @CliName(name = "inbound-filter-id", readonly = false)
    var inboundFilter: UUID = _
    @ZoomField(name = "outbound_filter_id", converter = classOf[UUIDConverter])
    @CliName(name = "outbound-filter-id", readonly = false)
    var outboundFilter: UUID = _

    @ZoomField(name = "peer_id", converter = classOf[UUIDConverter])
    @CliName(name = "peer-id", readonly = false)
    var peerId: UUID = _
    @ZoomField(name = "host_id", converter = classOf[UUIDConverter])
    @CliName(name = "host-id", readonly = false)
    var hostId: UUID = _
    @ZoomField(name = "interface_name")
    @CliName(name = "interface-name", readonly = false)
    var interfaceName: String = _
    @ZoomField(name = "vlan_id")
    @CliName(name = "vlan-id", readonly = false)
    var vlanId: Short = _

    @ZoomField(name = "port_group_ids", converter = classOf[UUIDConverter])
    @CliName(name = "port-group-ids")
    var portGroups: Set[UUID] = _
}

/** A network port. */
@CliName(name = "port")
class NetworkPort extends Port {
    @ZoomField(name = "network_id", converter = classOf[UUIDConverter])
    @CliName(name = "network-id", readonly = false)
    var networkId: UUID = _
}

/** A router port. */
@CliName(name = "port")
class RouterPort extends Port {
    @ZoomField(name = "router_id", converter = classOf[UUIDConverter])
    @CliName(name = "router-id", readonly = false)
    var routerId: UUID = _
    @ZoomField(name = "port_subnet", converter = classOf[IPSubnetConverter])
    @CliName(name = "port-subnet", readonly = false)
    var portSubnet: IPv4Subnet = _
    @ZoomField(name = "port_address", converter = classOf[IPAddressConverter])
    @CliName(name = "port-address", readonly = false)
    var portIp: IPv4Addr = _
    @ZoomField(name = "port_mac", converter = classOf[MACConverter])
    @CliName(name = "port-mac", readonly = false)
    var portMac: MAC = _
    @ZoomField(name = "route_ids", converter = classOf[UUIDConverter])
    @CliName(name = "route-ids")
    var routeIds: Set[UUID] = _
}

/** A VXLAN port. */
@CliName(name = "port")
class VxLanPort extends Port {
    @ZoomField(name = "network_id", converter = classOf[UUIDConverter])
    @CliName(name = "network-id", readonly = false)
    var networkId: UUID = _
    @ZoomField(name = "vtep_id", converter = classOf[UUIDConverter])
    @CliName(name = "vtep-id", readonly = false)
    var vtepId: UUID = _
}

sealed class PortFactory extends ZoomConvert.Factory[Port, Topology.Port] {
    override def getType(proto: Topology.Port): Class[_ <: Port] = {
        if (proto.hasVtepId) classOf[VxLanPort]
        else if (proto.hasNetworkId) classOf[NetworkPort]
        else if (proto.hasRouterId) classOf[RouterPort]
        else classOf[Port]
    }
}
