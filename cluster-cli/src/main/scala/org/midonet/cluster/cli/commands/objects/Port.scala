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
class Port extends ZoomObject {

    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    var id: UUID = _
    @ZoomField(name = "inbound_filter_id", converter = classOf[UUIDConverter])
    var inboundFilter: UUID = _
    @ZoomField(name = "outbound_filter_id", converter = classOf[UUIDConverter])
    var outboundFilter: UUID = _
    @ZoomField(name = "tunnel_key")
    var tunnelKey: Long = _
    @ZoomField(name = "port_group_ids", converter = classOf[UUIDConverter])
    var portGroups: Set[UUID] = Set.empty
    @ZoomField(name = "peer_id", converter = classOf[UUIDConverter])
    var peerId: UUID = _
    @ZoomField(name = "host_id", converter = classOf[UUIDConverter])
    var hostId: UUID = _
    @ZoomField(name = "interface_name")
    var interfaceName: String = _
    @ZoomField(name = "admin_state_up")
    var adminStateUp: Boolean = true
    @ZoomField(name = "vlan_id")
    var vlanId: Short = _

    override def toString =
        s"id=$id outboundFilter=$outboundFilter inboundFilter=$inboundFilter " +
        s"tunnelKey=$tunnelKey portGroups=$portGroups peerId=$peerId " +
        s"hostId=$hostId interfaceName=$interfaceName adminStateUp=$adminStateUp " +
        s"vlanId=$vlanId "
}

/** A VXLAN port. */
class VxLanPort extends Port {

    @ZoomField(name = "network_id", converter = classOf[UUIDConverter])
    var networkId: UUID = _

    @ZoomField(name = "vtep_mgmt_ip", converter = classOf[IPAddressConverter])
    var vtepMgmtIp: IPv4Addr = _
    @ZoomField(name = "vtep_mgmt_port")
    var vtepMgmtPort: Int = _
    @ZoomField(name = "vtep_tunnel_ip", converter = classOf[IPAddressConverter])
    var vtepTunnelIp: IPv4Addr = _
    @ZoomField(name = "vtep_tunnel_zone_id", converter = classOf[UUIDConverter])
    var vtepTunnelZoneId: UUID = _
    @ZoomField(name = "vtep_vni")
    var vtepVni: Int = _

    override def toString = super.toString +
        s"vtepMgmtIp=$vtepMgmtIp vtepMgmtPort=$vtepMgmtPort vtepTunnelIp=$vtepTunnelIp " +
        s"vtepTunnelZoneId=$vtepTunnelZoneId vtepVni=$vtepVni"
}

/** A network port. */
class NetworkPort extends Port {

    @ZoomField(name = "network_id", converter = classOf[UUIDConverter])
    var networkId: UUID = _

    override def toString = super.toString + s"networkId=$networkId"

}

/** A router port. */
class RouterPort extends Port {

    @ZoomField(name = "router_id", converter = classOf[UUIDConverter])
    var routerId: UUID = _
    @ZoomField(name = "port_subnet", converter = classOf[IPSubnetConverter])
    var portSubnet: IPv4Subnet = _
    @ZoomField(name = "port_address", converter = classOf[IPAddressConverter])
    var portIp: IPv4Addr = _
    @ZoomField(name = "port_mac", converter = classOf[MACConverter])
    var portMac: MAC = null

    override def toString = super.toString +
        s"routerId=$routerId portSubnet=$portSubnet portIp=$portIp " +
        s"portMac=$portMac"

}

sealed class PortFactory extends ZoomConvert.Factory[Port, Topology.Port] {
    override def getType(proto: Topology.Port): Class[_ <: Port] = {
        if (proto.hasVtepMgmtIp) classOf[VxLanPort]
        else if (proto.hasNetworkId) classOf[NetworkPort]
        else if (proto.hasRouterId) classOf[RouterPort]
        else classOf[Port]
    }
}
