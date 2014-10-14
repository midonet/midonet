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

import org.midonet.cluster.data.{ZoomConvert, ZoomField, ZoomObject, ZoomClass}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddressConverter}
import org.midonet.cluster.util.IPSubnetUtil.{Converter => IPSubnetConverter}
import org.midonet.cluster.util.MACUtil.{Converter => MACConverter}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.packets.{MAC, IPv4Subnet, IPv4Addr}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag

@ZoomClass(clazz = classOf[Topology.Port], factory = classOf[PortFactory])
sealed trait Port extends ZoomObject {

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

    private var _deviceTag: FlowTag = _

    def isExterior: Boolean = this.hostId != null && this.interfaceName != null

    def isInterior: Boolean = this.peerId != null

    def isPlugged: Boolean = this.isInterior || this.isExterior

    override def afterFromProto(): Unit = {
        _deviceTag = FlowTagger.tagForDevice(id)
        super.afterFromProto()
    }

    def deviceId: UUID
    def deviceTag = _deviceTag
}

/** Logical port connected to a peer vtep gateway. This subtype holds the
  *  24 bits VxLan Network Identifier (vni key) of the logical switch this
  *  port belongs to as well as the underlay ip address of the vtep gateway, its
  *  tunnel end point, and the tunnel zone to which hosts willing to open tunnels
  *  to this VTEP should belong to determine their own endpoint IP.
  *  It is assumed that the vxlan key is holded in the 3 last signifant bytes
  *  of the vni int field. */
class VxLanPort extends Port {

    @ZoomField(name = "vxlan_mgmt_ip", converter = classOf[IPAddressConverter])
    var vxlanMgmtIp: IPv4Addr = _
    @ZoomField(name = "vxlan_mgmt_port")
    var vxlanMgmtPort: Int = _
    @ZoomField(name = "vxlan_tunnel_ip", converter = classOf[IPAddressConverter])
    var vxlanTunnelIp: IPv4Addr = _
    @ZoomField(name = "vxlan_tunnel_zone_id", converter = classOf[UUIDConverter])
    var vxlanTunnelZoneId: UUID = _
    @ZoomField(name = "vxlan_vni")
    var vxlanVni: Int = _

    override def deviceId = null
    override def isExterior = true
    override def isInterior = false
}

class BridgePort extends Port {

    @ZoomField(name = "network_id", converter = classOf[UUIDConverter])
    var networkId: UUID = _

    override def deviceId = networkId
}

class RouterPort extends Port {

    @ZoomField(name = "router_id", converter = classOf[UUIDConverter])
    var routerId: UUID = _
    @ZoomField(name = "port_subnet", converter = classOf[IPSubnetConverter])
    var portSubnet: IPv4Subnet = _
    @ZoomField(name = "port_address", converter = classOf[IPAddressConverter])
    var portIp: IPv4Addr = _
    @ZoomField(name = "port_mac", converter = classOf[MACConverter])
    var portMac: MAC = null

    private var _portAddr: IPv4Subnet = _

    override def deviceId = routerId

    override def afterFromProto(): Unit = {
        _portAddr = new IPv4Subnet(portIp, portSubnet.getPrefixLen)
        super.afterFromProto()
    }

    def portAddr = _portAddr
    def nwSubnet = _portAddr
}

sealed class PortFactory extends ZoomConvert.Factory[Port, Topology.Port] {
    override def getType(proto: Topology.Port): Class[_ <: Port] = {
        if (proto.hasNetworkId) classOf[BridgePort]
        else if (proto.hasRouterId) classOf[RouterPort]
        else if (proto.hasVxlanMgmtIp) classOf[VxLanPort]
        else throw new IllegalArgumentException("Unknown port type")
    }
}