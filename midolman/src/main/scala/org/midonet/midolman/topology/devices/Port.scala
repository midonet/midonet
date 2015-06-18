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
import javax.annotation.concurrent.Immutable

import scala.collection.JavaConverters._

import com.google.protobuf.Message

import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.data._
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddressConverter}
import org.midonet.cluster.util.IPSubnetUtil.{Converter => IPSubnetConverter}
import org.midonet.cluster.util.MACUtil.{Converter => MACConverter}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.midolman.state.PortConfig
import org.midonet.midolman.state.PortDirectory.{BridgePortConfig, RouterPortConfig, VxLanPortConfig}
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag

@Immutable
@ZoomClass(clazz = classOf[Topology.Port], factory = classOf[PortFactory])
sealed trait Port extends ZoomObject with VirtualDevice with Cloneable {

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

    private[topology] var _active: Boolean = false

    private var _deviceTag: FlowTag = _
    private var _txTag: FlowTag = _
    private var _rxTag: FlowTag = _

    def isExterior: Boolean = this.hostId != null && this.interfaceName != null

    def isInterior: Boolean = this.peerId != null

    def isPlugged: Boolean = this.isInterior || this.isExterior

    def isActive: Boolean = _active

    override def afterFromProto(message: Message): Unit = {
        _deviceTag = FlowTagger.tagForDevice(id)
        _txTag = FlowTagger.tagForPortTx(id)
        _rxTag = FlowTagger.tagForPortRx(id)
        super.afterFromProto(message)
    }

    override def deviceTag = _deviceTag
    def txTag = _txTag
    def rxTag = _rxTag

    def deviceId: UUID

    def copy(active: Boolean): this.type = {
        val port = super.clone().asInstanceOf[this.type]
        port._active = active
        port
    }

    override def equals(obj: Any): Boolean = obj match {
        case port: Port =>
            id == port.id && inboundFilter == port.inboundFilter &&
            outboundFilter == port.outboundFilter && tunnelKey == port.tunnelKey &&
            portGroups == port.portGroups && peerId == port.peerId &&
            hostId == port.hostId && interfaceName == port.interfaceName &&
            adminStateUp == port.adminStateUp && vlanId == port.vlanId &&
            _active == port._active

        case _ => false
    }

    override def hashCode: Int =
        Objects.hashCode(id, inboundFilter, outboundFilter, tunnelKey,
                         portGroups, peerId, hostId, interfaceName,
                         adminStateUp, vlanId, _active)

    override def toString =
        s"id=$id active=$isActive adminStateUp=$adminStateUp " +
        s"inboundFilter=$inboundFilter outboundFilter=$outboundFilter " +
        s"tunnelKey=$tunnelKey portGroups=$portGroups peerId=$peerId " +
        s"hostId=$hostId interfaceName=$interfaceName vlanId=$vlanId"
}

/** Logical port connected to a peer VTEP gateway. This subtype holds the 24
  * bits VxLan Network Identifier (VNI key) of the logical switch this port
  * belongs to as well as the underlay ip address of the VTEP gateway, its
  * tunnel end point, and the tunnel zone to which hosts willing to open tunnels
  * to this VTEP should belong to determine their own endpoint IP. It is assumed
  * that the VXLAN key is held in the 3 last significant bytes of the VNI int
  * field. */
class VxLanPort extends Port {

    @ZoomField(name = "network_id", converter = classOf[UUIDConverter])
    var networkId: UUID = _
    @ZoomField(name = "vtep_id", converter = classOf[UUIDConverter])
    var vtepId: UUID = _

    // These are legacy fields that will not be present in the Proto-based
    // models, and instead be replaced with a vtepId: UUID
    @Deprecated
    var vtepMgmtIp: IPv4Addr = _
    @Deprecated
    var vtepMgmtPort: Int = _
    @Deprecated
    var vtepTunnelIp: IPv4Addr = _
    @Deprecated
    var vtepTunnelZoneId: UUID = _
    @Deprecated
    var vtepVni: Int = _

    override def deviceId = networkId
    override def isExterior = true
    override def isInterior = false
    override def isActive = true

    override def equals(obj: Any): Boolean = obj match {
        case port: VxLanPort =>
            super.equals(obj) &&
            networkId == port.networkId && vtepId == port.vtepId

        case _ => false
    }

    override def hashCode: Int =
        Objects.hashCode(super.hashCode, networkId, vtepId)

    override def toString =
        s"VxLanPort [${super.toString} networkId=$networkId vtepId=$vtepId]"
}

class OverlayVtepTunPort extends Port {

    @ZoomField(name = "overlay_vtep_id", converter = classOf[UUIDConverter])
    var vtepId: UUID = _
    @ZoomField(name = "port_mac", converter = classOf[MACConverter])
    var portMac: MAC = _
    @ZoomField(name = "port_address", converter = classOf[IPAddressConverter])
    var portIp: IPv4Addr = _

    override def deviceId = vtepId

    override def equals(obj: Any): Boolean = obj match {
        case port: OverlayVtepTunPort =>
            super.equals(obj) && vtepId == port.vtepId &&
            portIp == port.portIp && portMac == port.portMac
        case _ => false
    }

    override def hashCode: Int =
        Objects.hashCode(super.hashCode, vtepId)

    override def toString =
        s"OlayVtepTunPort [${super.toString} vtepId=$vtepId]"
}

class OverlayVtepSwitchPort extends Port {

    @ZoomField(name = "overlay_vtep_log_switch_id",
               converter = classOf[UUIDConverter])
    var vtepSwitchId: UUID = _

    override def deviceId = vtepSwitchId

    override def equals(obj: Any): Boolean = obj match {
        case port: OverlayVtepSwitchPort =>
            super.equals(obj) && vtepSwitchId == port.vtepSwitchId
        case _ => false
    }

    override def hashCode: Int =
        Objects.hashCode(super.hashCode, vtepSwitchId)

    override def toString =
        s"OlayVtepSwitchPort [${super.toString} switchId=$vtepSwitchId]"
}

class BridgePort extends Port {

    @ZoomField(name = "network_id", converter = classOf[UUIDConverter])
    var networkId: UUID = _

    override def deviceId = networkId

    override def equals(obj: Any): Boolean = obj match {
        case port: BridgePort =>
            super.equals(obj) && networkId == port.networkId

        case _ => false
    }

    override def hashCode: Int =
        Objects.hashCode(super.hashCode, networkId)

    override def toString =
        s"BridgePort [${super.toString} networkId=$networkId]"
}

class RouterPort extends Port {

    @ZoomField(name = "router_id", converter = classOf[UUIDConverter])
    var routerId: UUID = _
    @ZoomField(name = "port_subnet", converter = classOf[IPSubnetConverter])
    var portSubnet: IPv4Subnet = _
    @ZoomField(name = "port_address", converter = classOf[IPAddressConverter])
    var portIp: IPv4Addr = _
    @ZoomField(name = "port_mac", converter = classOf[MACConverter])
    var portMac: MAC = _
    @ZoomField(name = "route_ids", converter = classOf[UUIDConverter])
    var routeIds: Set[UUID] = Set.empty

    private var _portAddr: IPv4Subnet = _

    override def deviceId = routerId

    override def afterFromProto(message: Message): Unit = {
        _portAddr = new IPv4Subnet(portIp, portSubnet.getPrefixLen)
        super.afterFromProto(message)
    }

    def portAddr = _portAddr
    def nwSubnet = _portAddr

    override def equals(obj: Any): Boolean = obj match {
        case port: RouterPort =>
            super.equals(obj) &&
            routerId == port.routerId && portSubnet == port.portSubnet &&
            portIp == port.portIp && portMac == port.portMac &&
            routeIds == port.routeIds

        case _ => false
    }

    override def hashCode: Int =
        Objects.hashCode(super.hashCode, routerId, portSubnet, portIp, portMac,
                         routeIds)

    override def toString =
        s"RouterPort [${super.toString} routerId=$routerId " +
        s"portSubnet=$portSubnet portIp=$portIp portMac=$portMac " +
        s"routeIds=$routeIds]"
}

sealed class PortFactory extends ZoomConvert.Factory[Port, Topology.Port] {
    override def getType(proto: Topology.Port): Class[_ <: Port] = {
        if (proto.hasVtepId) classOf[VxLanPort]
        else if (proto.hasNetworkId) classOf[BridgePort]
        else if (proto.hasOverlayVtepLogSwitchId) classOf[OverlayVtepSwitchPort]
        else if (proto.hasOverlayVtepId) classOf[OverlayVtepTunPort]
        else if (proto.hasRouterId) classOf[RouterPort]
        else throw new ConvertException("Unknown port type")
    }
}

object PortFactory {
    @Deprecated
    def fromPortConfig(config: PortConfig): Port = {
        val port = config match {
            case cfg: BridgePortConfig =>
                val p = new BridgePort
                p.networkId = config.device_id
                p
            case cfg: RouterPortConfig =>
                val p = new RouterPort
                p.routerId = config.device_id
                p.portIp = IPv4Addr.fromString(cfg.getPortAddr)
                p.portSubnet = new IPv4Subnet(cfg.nwAddr, cfg.nwLength)
                p.portMac = cfg.getHwAddr
                p
            case cfg: VxLanPortConfig =>
                val p: VxLanPort = new VxLanPort
                p.vtepMgmtIp = IPv4Addr.fromString(cfg.mgmtIpAddr)
                p.vtepTunnelIp = IPv4Addr.fromString(cfg.tunIpAddr)
                p.vtepTunnelZoneId = cfg.tunnelZoneId
                p.vtepVni = cfg.vni
                p.networkId = cfg.device_id
                p
            case _ => throw new IllegalArgumentException("Unknown port type")
        }
        port.id = config.id
        port.tunnelKey = config.tunnelKey
        port.adminStateUp = config.adminStateUp
        port.inboundFilter = config.inboundFilter
        port.outboundFilter = config.outboundFilter
        port.peerId = config.getPeerId
        port.hostId = config.getHostId
        port.interfaceName = config.getInterfaceName
        if (config.portGroupIDs != null) {
            port.portGroups = config.portGroupIDs.asScala.toSet
        }
        port.afterFromProto(null)
        port
    }
}
