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

package org.midonet.midolman.simulation

import java.util.UUID
import scala.collection.JavaConverters._

import akka.actor.ActorSystem

import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.util.{IPSubnetUtil, IPAddressUtil, UUIDUtil}
import org.midonet.midolman.PacketWorkflow.{Drop, SimulationResult}
import org.midonet.midolman.state.PortConfig
import org.midonet.midolman.state.PortDirectory.{BridgePortConfig, RouterPortConfig, VxLanPortConfig}
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import org.midonet.sdn.flows.FlowTagger

object Port {
    import IPAddressUtil._
    import IPSubnetUtil._

    private implicit def uuidFromProto(uuid: Commons.UUID): UUID =
        new UUID(uuid.getMsb, uuid.getLsb)

    private implicit def jlistToSSet(from: java.util.List[Commons.UUID]): Set[UUID] =
        if (from ne null) from.asScala.toSet map UUIDUtil.fromProto else Set.empty

    private implicit def jsetToSSet(from: java.util.Set[UUID]): Set[UUID] =
        if (from ne null) from.asScala.toSet else Set.empty


    def apply(proto: Topology.Port): Port = {
        if (proto.hasVtepId)
            vxLanPort(proto)
        else if (proto.hasNetworkId)
            bridgePort(proto)
        else if (proto.hasRouterId)
            routerPort(proto)
        else
            throw new ConvertException("Unknown port type")
    }

    @Deprecated
    def apply(config: PortConfig): Port = {
        config match {
            case p: BridgePortConfig => bridgePort(p)
            case p: RouterPortConfig => routerPort(p)
            case p: VxLanPortConfig => vxLanPort(p)
            case _ => throw new IllegalArgumentException("Unknown port type")
        }
    }

    private def bridgePort(p: Topology.Port) = BridgePort(
            p.getId,
            if (p.hasInboundFilterId) p.getInboundFilterId else null,
            if (p.hasOutboundFilterId) p.getOutboundFilterId else null,
            p.getTunnelKey,
            if (p.hasPeerId) p.getPeerId else null,
            if (p.hasHostId) p.getHostId else null,
            if (p.hasInterfaceName) p.getInterfaceName else null,
            p.getAdminStateUp,
            p.getPortGroupIdsList, false, p.getVlanId.toShort,
            if (p.hasNetworkId) p.getNetworkId else null)

    private def routerPort(p: Topology.Port) = RouterPort(
            p.getId,
            if (p.hasInboundFilterId) p.getInboundFilterId else null,
            if (p.hasOutboundFilterId) p.getOutboundFilterId else null,
            p.getTunnelKey,
            if (p.hasPeerId) p.getPeerId else null,
            if (p.hasHostId) p.getHostId else null,
            if (p.hasInterfaceName) p.getInterfaceName else null,
            p.getAdminStateUp, p.getPortGroupIdsList, false,
            if (p.hasRouterId) p.getRouterId else null,
            if (p.hasPortSubnet) fromV4Proto(p.getPortSubnet) else null,
            if (p.hasPortAddress) toIPv4Addr(p.getPortAddress) else null,
            if (p.hasPortMac) MAC.fromString(p.getPortMac) else null,
            p.getRouteIdsList)

    private def vxLanPort(p: Topology.Port) = VxLanPort(
            p.getId,
            if (p.hasInboundFilterId) p.getInboundFilterId else null,
            if (p.hasOutboundFilterId) p.getOutboundFilterId else null,
            p.getTunnelKey,
            if (p.hasPeerId) p.getPeerId else null,
            p.getAdminStateUp,
            p.getPortGroupIdsList,
            if (p.hasVtepId) p.getVtepId else null,
            if (p.hasNetworkId) p.getNetworkId else null)

    @Deprecated
    private def bridgePort(p: BridgePortConfig) = BridgePort(
            p.id, p.inboundFilter, p.outboundFilter, p.tunnelKey, p.peerId, p.hostId,
            p.interfaceName, p.adminStateUp, p.portGroupIDs, false,
            if (p.vlanId ne null) p.vlanId else Bridge.UntaggedVlanId,
            p.device_id)

    @Deprecated
    private def routerPort(p: RouterPortConfig) = RouterPort(
            p.id, p.inboundFilter, p.outboundFilter, p.tunnelKey, p.peerId, p.hostId,
            p.interfaceName, p.adminStateUp, p.portGroupIDs, false, p.device_id,
            new IPv4Subnet(p.nwAddr, p.nwLength),
            IPv4Addr.fromString(p.getPortAddr), p.getHwAddr, null)

    @Deprecated
    private def vxLanPort(p: VxLanPortConfig) = VxLanPort(
            p.id, p.inboundFilter, p.outboundFilter, p.tunnelKey, p.peerId,
            p.adminStateUp, p.portGroupIDs, new UUID(1, 39), p.device_id,
            IPv4Addr.fromString(p.mgmtIpAddr), p.mgmtPort,
            IPv4Addr.fromString(p.tunIpAddr), p.tunnelZoneId, p.vni)
}

trait Port extends VirtualDevice with Coordinator.Device with Cloneable {
    def id: UUID
    def inboundFilter: UUID
    def outboundFilter: UUID
    def tunnelKey: Long
    def peerId: UUID
    def hostId: UUID
    def interfaceName: String
    def adminStateUp: Boolean
    def portGroups: Set[UUID] = Set.empty
    def isActive: Boolean = false
    def deviceId: UUID
    def vlanId: Short = Bridge.UntaggedVlanId

    def toggleActive(active: Boolean) = this

    val deviceTag = FlowTagger.tagForPort(id)
    val txTag = FlowTagger.tagForPortTx(id)
    val rxTag = FlowTagger.tagForPortRx(id)

    def isExterior: Boolean = this.hostId != null && this.interfaceName != null

    def isInterior: Boolean = this.peerId != null

    def isPlugged: Boolean = this.isInterior || this.isExterior

    def ingress(context: PacketContext)(implicit as: ActorSystem): SimulationResult = {
        Drop
    }

    def egress(context: PacketContext)(implicit as: ActorSystem): SimulationResult = {
        Drop
    }

    def process(context: PacketContext): SimulationResult = {
        Drop
    }
}

object BridgePort {
    def random = BridgePort(UUID.randomUUID(), networkId = UUID.randomUUID)
}

case class BridgePort(override val id: UUID,
                      override val inboundFilter: UUID = null,
                      override val outboundFilter: UUID = null,
                      override val tunnelKey: Long = 0,
                      override val peerId: UUID = null,
                      override val hostId: UUID = null,
                      override val interfaceName: String = null,
                      override val adminStateUp: Boolean = true,
                      override val portGroups: Set[UUID] = Set.empty,
                      override val isActive: Boolean = false,
                      override val vlanId: Short = Bridge.UntaggedVlanId,
                      networkId: UUID) extends Port {

    override def toggleActive(active: Boolean) = copy(isActive = active)
    override def deviceId = networkId
}

case class RouterPort(override val id: UUID,
                      override val inboundFilter: UUID = null,
                      override val outboundFilter: UUID = null,
                      override val tunnelKey: Long = 0,
                      override val peerId: UUID = null,
                      override val hostId: UUID = null,
                      override val interfaceName: String = null,
                      override val adminStateUp: Boolean = true,
                      override val portGroups: Set[UUID] = Set.empty,
                      override val isActive: Boolean = false,
                      routerId: UUID,
                      portSubnet: IPv4Subnet,
                      portIp: IPv4Addr,
                      portMac: MAC,
                      routeIds: Set[UUID] = Set.empty) extends Port {

    val _portAddr = new IPv4Subnet(portIp, portSubnet.getPrefixLen)

    override def toggleActive(active: Boolean) = copy(isActive = active)

    override def deviceId = routerId

    def portAddr = _portAddr
    def nwSubnet = _portAddr
}

case class VxLanPort(override val id: UUID,
                     override val inboundFilter: UUID = null,
                     override val outboundFilter: UUID = null,
                     override val tunnelKey: Long = 0,
                     override val peerId: UUID = null,
                     override val adminStateUp: Boolean = true,
                     override val portGroups: Set[UUID] = Set.empty,
                     vtepId: UUID,
                     networkId: UUID,
                     vtepMgmtIp: IPv4Addr = null,
                     vtepMgmtPort: Int = 0,
                     vtepTunnelIp: IPv4Addr = null,
                     vtepTunnelZoneId: UUID = null,
                     vtepVni: Int = 0) extends Port {

    override def hostId = null
    override def interfaceName = null
    override def deviceId = networkId
    override def isExterior = true
    override def isInterior = false
    override def isActive = true

    override def toggleActive(active: Boolean) = this
}
