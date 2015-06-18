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

import java.util.{ArrayList => JArrayList, List => JList, UUID}

import scala.collection.JavaConverters._

import akka.actor.ActorSystem

import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, Drop, ErrorDrop, SimStep, SimulationResult}
import org.midonet.midolman.simulation.Port.NO_MIRRORS
import org.midonet.midolman.simulation.Simulator.{ContinueWith, SimHook, ToPortAction}
import org.midonet.midolman.state.PortConfig
import org.midonet.midolman.state.PortDirectory.{BridgePortConfig, RouterPortConfig, VxLanPortConfig}
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import org.midonet.sdn.flows.FlowTagger

object Port {
    import org.midonet.cluster.util.IPAddressUtil._
    import org.midonet.cluster.util.IPSubnetUtil._
    import org.midonet.cluster.util.UUIDUtil.{fromProto, fromProtoList}

    val NO_MIRRORS = new JArrayList[UUID]()

    private implicit def jlistToSSet(from: java.util.List[Commons.UUID]): Set[UUID] =
        if (from ne null) from.asScala.toSet map UUIDUtil.fromProto else Set.empty

    private def jSetToJArrayList(from: java.util.Set[UUID]): JArrayList[UUID] =
        if (from ne null) new JArrayList(from) else new JArrayList(0)

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
            if (p.hasNetworkId) p.getNetworkId else null,
            p.getInboundMirrorsList,
            p.getOutboundMirrorsList)

    /**
     * This class implements the MapConverter trait to do the conversion between
     * tuples of type (MAC, IPAddr) and HostToIp protos.
     */
    private def macVtepListToMap(vteps: JList[Topology.MacIp]): Map[MAC, IPv4Addr] = {
        val map = Map.empty[MAC, IPv4Addr]
        var i = 0
        while (i < vteps.size()) {
            val vt = vteps.get(i)
            map.updated(MAC.fromString(vt.getMac),
                        toIPv4Addr(vt.getIp))
        }
        map
    }

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
            p.getRouteIdsList,
            p.getInboundMirrorsList,
            p.getOutboundMirrorsList,
            if (p.hasVni) p.getVni else 0,
            if (p.hasLocalVtep) toIPv4Addr(p.getLocalVtep) else null,
            if (p.hasDefaultRemoteVtep) toIPv4Addr(p.getDefaultRemoteVtep) else null,
            if (p.getRemoteVtepsCount == 0) macVtepListToMap(p.getRemoteVtepsList) else Map.empty[MAC, IPv4Addr],
            p.getOffRampVxlan)

    private def vxLanPort(p: Topology.Port) = VxLanPort(
            p.getId,
            if (p.hasInboundFilterId) p.getInboundFilterId else null,
            if (p.hasOutboundFilterId) p.getOutboundFilterId else null,
            p.getTunnelKey,
            if (p.hasPeerId) p.getPeerId else null,
            p.getAdminStateUp,
            p.getPortGroupIdsList,
            if (p.hasVtepId) p.getVtepId else null,
            if (p.hasNetworkId) p.getNetworkId else null,
            inboundMirrors = p.getInboundMirrorsList,
            outboundMirrors = p.getOutboundMirrorsList)

    @Deprecated
    private def bridgePort(p: BridgePortConfig) = BridgePort(
            p.id, p.inboundFilter, p.outboundFilter, p.tunnelKey, p.peerId, p.hostId,
            p.interfaceName, p.adminStateUp, jSetToJArrayList(p.portGroupIDs), false,
            if (p.vlanId ne null) p.vlanId else Bridge.UntaggedVlanId,
            p.device_id, new JArrayList[UUID](), new JArrayList[UUID]())

    @Deprecated
    private def routerPort(p: RouterPortConfig) = RouterPort(
            p.id, p.inboundFilter, p.outboundFilter, p.tunnelKey, p.peerId, p.hostId,
            p.interfaceName, p.adminStateUp, jSetToJArrayList(p.portGroupIDs), false,
            p.device_id, new IPv4Subnet(p.nwAddr, p.nwLength),
            IPv4Addr.fromString(p.getPortAddr), p.getHwAddr, null,
            new JArrayList[UUID](), new JArrayList[UUID]())

    @Deprecated
    private def vxLanPort(p: VxLanPortConfig) = VxLanPort(
            p.id, p.inboundFilter, p.outboundFilter, p.tunnelKey, p.peerId,
            p.adminStateUp, jSetToJArrayList(p.portGroupIDs), new UUID(1, 39), p.device_id,
            IPv4Addr.fromString(p.mgmtIpAddr), p.mgmtPort,
            IPv4Addr.fromString(p.tunIpAddr), p.tunnelZoneId, p.vni,
            new JArrayList[UUID](), new JArrayList[UUID]())
}

trait Port extends VirtualDevice with InAndOutFilters with MirroringDevice with Cloneable {
    def id: UUID
    def inboundFilter: UUID
    def outboundFilter: UUID
    def tunnelKey: Long
    def peerId: UUID
    def hostId: UUID
    def interfaceName: String
    def adminStateUp: Boolean
    def portGroups: JArrayList[UUID] = new JArrayList(0)
    def isActive: Boolean = false
    def deviceId: UUID
    def vlanId: Short = Bridge.UntaggedVlanId

    override def infilter = inboundFilter
    override def outfilter = outboundFilter

    val action = ToPortAction(id)

    def toggleActive(active: Boolean) = this
    def updateInboundFilter(filter: UUID) = this

    val deviceTag = FlowTagger.tagForPort(id)
    val flowStateTag = FlowTagger.tagForFlowStateDevice(id)
    val txTag = FlowTagger.tagForPortTx(id)
    val rxTag = FlowTagger.tagForPortRx(id)

    def isExterior: Boolean = this.hostId != null && this.interfaceName != null

    def isInterior: Boolean = this.peerId != null

    def isPlugged: Boolean = this.isInterior || this.isExterior

    protected def device(implicit as: ActorSystem): ForwardingDevice

    private[this] val emit = ContinueWith(if (isExterior) {
        (context, as) =>
            context.calculateActionsFromMatchDiff()
            context.log.debug("Emitting packet from vport {}", id)
            context.addVirtualAction(action)
            context.trackConnection(deviceId)
            AddVirtualWildcardFlow
    } else if (isInterior) {
        (context, as) =>
            implicit val actorSystem: ActorSystem = as
            tryAsk[Port](peerId).ingress(context, as)
    } else {
        (context, as) =>
            context.log.warn("Port {} is unplugged", id)
            ErrorDrop
    })

    def ingress(implicit context: PacketContext, as: ActorSystem): SimulationResult = {
        context.log.debug(s"Ingressing port $id")
        if (context.devicesTraversed >= Simulator.MAX_DEVICES_TRAVERSED) {
            context.log.debug(s"Dropping packet that traversed too many devices "+
                s"(${context.devicesTraversed}}), possible topology loop.")
            ErrorDrop
        } else {
            context.addFlowTag(deviceTag)
            context.addFlowTag(rxTag)
            context.inPortId = id
            mirroringInbound(context, portIngress, as)
        }
    }

    def egress(context: PacketContext, as: ActorSystem): SimulationResult = {
        context.log.debug(s"Egressing port $id")
        context.addFlowTag(deviceTag)
        context.addFlowTag(txTag)
        context.outPortId = id
        filterOut(context, as, continueOut)
    }

    private[this] val ingressDevice: SimStep = (context, as) => {
        val dev = device(as)
        dev.continue(context, dev.process(context))(as)
    }

    protected val continueIn: SimStep = (c, as) => ingressDevice(c, as)
    protected val continueOut: SimStep = (c, as) => mirroringOutbound(c, emit, as)

    private val portIngress = ContinueWith((context, as) => {
        filterIn(context, as, continueIn)
    })

    override protected val preIn: SimHook = (c, as) => {
        if (isExterior && (portGroups ne null))
            c.portGroups = portGroups
        c.inPortId = id
        c.outPortId = null
    }

    override protected val preOut: SimHook = (c, as) => {
        c.outPortId = id
    }

    override def toString =
        s"id=$id active=$isActive adminStateUp=$adminStateUp " +
        s"inboundFilter=$inboundFilter outboundFilter=$outboundFilter " +
        s"tunnelKey=$tunnelKey portGroups=$portGroups peerId=$peerId " +
        s"hostId=$hostId interfaceName=$interfaceName vlanId=$vlanId"

}

object BridgePort {
    def random = BridgePort(UUID.randomUUID, networkId = UUID.randomUUID)
}

case class BridgePort(override val id: UUID,
                      override val inboundFilter: UUID = null,
                      override val outboundFilter: UUID = null,
                      override val tunnelKey: Long = 0,
                      override val peerId: UUID = null,
                      override val hostId: UUID = null,
                      override val interfaceName: String = null,
                      override val adminStateUp: Boolean = true,
                      override val portGroups: JArrayList[UUID] = new JArrayList(0),
                      override val isActive: Boolean = false,
                      override val vlanId: Short = Bridge.UntaggedVlanId,
                      networkId: UUID,
                      override val inboundMirrors: JArrayList[UUID] = NO_MIRRORS,
                      override val outboundMirrors: JArrayList[UUID] = NO_MIRRORS)
    extends Port {

    override def toggleActive(active: Boolean) = copy(isActive = active)
    override def updateInboundFilter(filter: UUID) = copy(inboundFilter = filter)
    override def deviceId = networkId

    protected def device(implicit as: ActorSystem) = tryAsk[Bridge](networkId)

    override def egress(context: PacketContext, as: ActorSystem): SimulationResult = {
        if (id == context.inPortId) {
            Drop
        } else {
            context.log.debug(s"Egressing port $id")
            context.addFlowTag(deviceTag)
            context.addFlowTag(txTag)
            context.outPortId = id
            if ((vlanId > 0) && context.wcmatch.isVlanTagged)
                context.wcmatch.removeVlanId(vlanId)
            filterOut(context, as, continueOut)
        }
    }

    override def toString =
        s"BridgePort [${super.toString} networkId=$networkId]"
}

case class RouterPort(override val id: UUID,
                      override val inboundFilter: UUID = null,
                      override val outboundFilter: UUID = null,
                      override val tunnelKey: Long = 0,
                      override val peerId: UUID = null,
                      override val hostId: UUID = null,
                      override val interfaceName: String = null,
                      override val adminStateUp: Boolean = true,
                      override val portGroups: JArrayList[UUID] = new JArrayList(0),
                      override val isActive: Boolean = false,
                      routerId: UUID,
                      portSubnet: IPv4Subnet,
                      portAddress: IPv4Addr,
                      portMac: MAC,
                      routeIds: Set[UUID] = Set.empty,
                      override val inboundMirrors: JList[UUID] = NO_MIRRORS,
                      override val outboundMirrors: JList[UUID] = NO_MIRRORS,
                      vni: Int = 0,
                      localVtep: IPv4Addr = null,
                      defaultRemoteVtep: IPv4Addr = null,
                      remoteVteps: Map[MAC, IPv4Addr] = null,
                      offRampVxlan: Boolean = false)
    extends Port {

    protected def device(implicit as: ActorSystem) = tryAsk[Router](routerId)

    override def toggleActive(active: Boolean) = copy(isActive = active)
    override def updateInboundFilter(filter: UUID) = copy(inboundFilter = filter)

    override def deviceId = routerId

    override protected def reject(context: PacketContext, as: ActorSystem): Unit = {
        implicit val _as: ActorSystem = as
        if (context.inPortId ne null) {
            if (context.inPortId eq id)
                sendIcmpProhibited(this, context)
            else
                sendIcmpProhibited(tryAsk[RouterPort](context.inPortId), context)
        }
    }

    private def sendIcmpProhibited(from: RouterPort, context: PacketContext): Unit = {
        import org.midonet.midolman.simulation.Icmp.IPv4Icmp._
        val ethOpt = unreachableProhibitedIcmp(from, context)
        if (ethOpt.isDefined)
            context.addGeneratedPacket(from.id, ethOpt.get)
    }

    override def toString =
        s"RouterPort [${super.toString} routerId=$routerId " +
        s"portSubnet=$portSubnet portAddress=$portAddress portMac=$portMac " +
        s"routeIds=$routeIds]"
}

case class VxLanPort(override val id: UUID,
                     override val inboundFilter: UUID = null,
                     override val outboundFilter: UUID = null,
                     override val tunnelKey: Long = 0,
                     override val peerId: UUID = null,
                     override val adminStateUp: Boolean = true,
                     override val portGroups: JArrayList[UUID] = new JArrayList(0),
                     vtepId: UUID,
                     networkId: UUID,
                     vtepMgmtIp: IPv4Addr = null,
                     vtepMgmtPort: Int = 0,
                     vtepTunnelIp: IPv4Addr = null,
                     vtepTunnelZoneId: UUID = null,
                     vtepVni: Int = 0,
                     override val inboundMirrors: JList[UUID] = NO_MIRRORS,
                     override val outboundMirrors: JList[UUID] = NO_MIRRORS)
    extends Port {

    override def hostId = null
    override def interfaceName = null
    override def deviceId = networkId
    override def isExterior = true
    override def isInterior = false
    override def isActive = true

    protected def device(implicit as: ActorSystem) = tryAsk[Bridge](networkId)

    override def toggleActive(active: Boolean) = this
    override def updateInboundFilter(filter: UUID) = copy(inboundFilter = filter)

    override def toString =
        s"VxLanPort [${super.toString} networkId=$networkId vtepId=$vtepId]"
}
