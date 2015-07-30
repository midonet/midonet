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
import java.util.{Set => JSet}
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.Simulator.{SimStep, ToPortAction}
import org.midonet.midolman.topology.VirtualTopologyActor._

import scala.collection.JavaConverters._

import akka.actor.ActorSystem

import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.util.{IPSubnetUtil, IPAddressUtil, UUIDUtil}
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, ErrorDrop, Drop, SimulationResult}
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

    private implicit def jlistToJSet(from: java.util.List[Commons.UUID]): JSet[UUID] =
        if (from ne null) (from.asScala.toSet map UUIDUtil.fromProto).asJava else null

    private implicit def jsetToSSet(from: JSet[UUID]): Set[UUID] =
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

trait Port extends VirtualDevice with Cloneable {
    def id: UUID
    def inboundFilter: UUID
    def outboundFilter: UUID
    def tunnelKey: Long
    def peerId: UUID
    def hostId: UUID
    def interfaceName: String
    def adminStateUp: Boolean
    def portGroups: JSet[UUID] = null
    def isActive: Boolean = false
    def deviceId: UUID
    def vlanId: Short = Bridge.UntaggedVlanId

    val action = ToPortAction(id)

    def toggleActive(active: Boolean) = this

    val deviceTag = FlowTagger.tagForPort(id)
    val txTag = FlowTagger.tagForPortTx(id)
    val rxTag = FlowTagger.tagForPortRx(id)

    def isExterior: Boolean = this.hostId != null && this.interfaceName != null

    def isInterior: Boolean = this.peerId != null

    def isPlugged: Boolean = this.isInterior || this.isExterior

    protected def device(implicit as: ActorSystem): SimDevice

    private[this] val emit: SimStep = if (isExterior) {
        (context, as) =>
            context.calculateActionsFromMatchDiff()
            context.log.debug("Emitting packet from vport {}", id)
            context.addVirtualAction(action)
            context.trackConnection(deviceId)
            context.addTraceKeysForEgress()
            AddVirtualWildcardFlow
    } else if (isInterior) {
        (context, as) => Drop
            implicit val actorSystem: ActorSystem = as
            tryAsk[Port](peerId).ingress(context, as)
    } else {
        (context, as) =>
            context.log.warn("Port {} is unplugged", id)
            ErrorDrop
    }

    def ingress(implicit context: PacketContext, as: ActorSystem): SimulationResult = {
        if (context.devicesTraversed >= Simulator.MAX_DEVICES_TRAVERSED) {
            ErrorDrop
        } else {
            context.addFlowTag(deviceTag)
            context.addFlowTag(rxTag)
            ingressAdminState(context, as)
        }
    }

    private[this] val ingressDevice: SimStep = (context, as) => {
        val dev = device(as)
        dev.continue(context, dev.process(context))(as)
    }

    private[this] val inFilter: SimStep = if (inboundFilter ne null) {
        (context, as) => filter(context, inboundFilter, ingressDevice, as)
    } else {
        (context, as) => ingressDevice(context, as)
    }

    protected val outFilter: SimStep = if (outboundFilter ne null) {
        (context, as) => filter(context, outboundFilter, emit, as)
    } else {
        (context, as) => emit(context, as)
    }

    protected def ingressUp: SimStep = (context, as) => {
        if (isExterior && (portGroups ne null))
            context.portGroups = portGroups
        context.inPortId = id
        context.outPortId = null
        inFilter(context, as)
    }

    protected def egressUp: SimStep = (context, as) => {
        context.outPortId = id
        context.inPortId = null
        outFilter(context, as)
    }

    protected def egressDown: SimStep = (c, as) => Drop
    protected def ingressDown: SimStep = (c, as) => Drop

    private[this] val ingressAdminState: SimStep = if (adminStateUp) ingressUp else ingressDown

    private[this] val egressAdminState: SimStep = if (adminStateUp) egressUp else egressDown


    protected def filter(context: PacketContext, filterId: UUID, next: SimStep, as: ActorSystem): SimulationResult = {
        implicit val _as: ActorSystem = as
        val chain = tryAsk[Chain](filterId)
        val result = Chain.apply(chain, context, id)
        result.action match {
            case RuleResult.Action.ACCEPT =>
                next(context, as)
            case RuleResult.Action.DROP | RuleResult.Action.REJECT =>
                Drop
            case other =>
                context.log.error("Port filter {} returned {} which was " +
                                  "not ACCEPT, DROP or REJECT.", filterId, other)
                ErrorDrop
        }
    }

    final def egress(context: PacketContext, as: ActorSystem): SimulationResult = {
        context.addFlowTag(deviceTag)
        context.addFlowTag(txTag)
        egressAdminState(context, as)
    }
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
                      override val portGroups: JSet[UUID] = null,
                      override val isActive: Boolean = false,
                      override val vlanId: Short = Bridge.UntaggedVlanId,
                      networkId: UUID) extends Port {

    override def toggleActive(active: Boolean) = copy(isActive = active)
    override def deviceId = networkId

    protected def device(implicit as: ActorSystem): SimDevice = tryAsk[Bridge](networkId)

    override protected def egressUp: SimStep = (context, as) => {
        if (id == context.inPortId) {
            Drop
        } else {
            if ((vlanId > 0) && context.wcmatch.isVlanTagged)
                context.wcmatch.removeVlanId(vlanId)
            context.outPortId = id
            outFilter(context, as)
        }
    }
}

case class RouterPort(override val id: UUID,
                      override val inboundFilter: UUID = null,
                      override val outboundFilter: UUID = null,
                      override val tunnelKey: Long = 0,
                      override val peerId: UUID = null,
                      override val hostId: UUID = null,
                      override val interfaceName: String = null,
                      override val adminStateUp: Boolean = true,
                      override val portGroups: JSet[UUID] = null,
                      override val isActive: Boolean = false,
                      routerId: UUID,
                      portSubnet: IPv4Subnet,
                      portIp: IPv4Addr,
                      portMac: MAC,
                      routeIds: Set[UUID] = Set.empty) extends Port {

    val _portAddr = new IPv4Subnet(portIp, portSubnet.getPrefixLen)

    protected def device(implicit as: ActorSystem): SimDevice = tryAsk[Router](routerId)

    override def toggleActive(active: Boolean) = copy(isActive = active)

    override def deviceId = routerId

    def portAddr = _portAddr
    def nwSubnet = _portAddr

    override protected def ingressDown: SimStep = (context, as) => {
        sendIcmpProhibited(this, context)
        Drop
    }

    override protected def egressDown: SimStep = (context, as) => {
        implicit val _as: ActorSystem = as
        if (context.inPortId ne null)
            sendIcmpProhibited(tryAsk[RouterPort](context.inPortId), context)
        Drop
    }

    private def sendIcmpProhibited(from: RouterPort, context: PacketContext): Unit = {
        import Icmp.IPv4Icmp._
        val ethOpt = unreachableProhibitedIcmp(from, context)
        if (ethOpt.isDefined) {
            context.addGeneratedPacket(from.id, ethOpt.get)
        }
    }
}

case class VxLanPort(override val id: UUID,
                     override val inboundFilter: UUID = null,
                     override val outboundFilter: UUID = null,
                     override val tunnelKey: Long = 0,
                     override val peerId: UUID = null,
                     override val adminStateUp: Boolean = true,
                     override val portGroups: JSet[UUID] = null,
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

    protected def device(implicit as: ActorSystem): SimDevice = tryAsk[Bridge](networkId)

    override def toggleActive(active: Boolean) = this
}
