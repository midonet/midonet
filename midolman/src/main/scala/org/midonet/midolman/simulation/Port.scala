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

import java.util.Collections.emptyList
import java.util.{UUID, List => JList}

import scala.collection.JavaConverters._
import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.models.Topology
import org.midonet.cluster.state.PortStateStorage.PortState
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.simulation.Simulator.{ContinueWith, SimHook, ToPortAction}
import org.midonet.midolman.topology.VirtualTopology.{VirtualDevice, tryGet}
import org.midonet.odp.FlowMatch
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger

object Port {
    import IPAddressUtil._
    import UUIDUtil.{fromProto, fromProtoList}

    // Defaults for a RouterPort containing only IPv6 addresses
    val DefaultIPv4Subnet = new IPv4Subnet(0, 0)
    val DefaultIPv4Address = new IPv4Addr(0)

    def apply(proto: Topology.Port,
              state: PortState,
              inFilters: JList[UUID],
              outFilters: JList[UUID],
              servicePorts: JList[UUID] = emptyList(),
              fipNatRules: JList[UUID] = emptyList(),
              peeringTable: StateTable[MAC, IPv4Addr] = StateTable.empty,
              qosPolicy: QosPolicy = null): Port = {
        if (proto.getSrvInsertionIdsCount > 0 && proto.hasNetworkId)
            servicePort(proto, state, inFilters)
        else if (proto.hasVtepId)
            vxLanPort(proto, state, inFilters, outFilters)
        else if (proto.hasNetworkId)
            bridgePort(proto, state, inFilters, outFilters,
                       servicePorts, qosPolicy)
        else if (proto.hasRouterId)
            routerPort(proto, state, inFilters, outFilters, fipNatRules,
                       peeringTable, qosPolicy)
        else
            throw new ConvertException("Unknown port type")
    }

    private def bridgePort(p: Topology.Port,
                           state: PortState,
                           inFilters: JList[UUID],
                           outFilters: JList[UUID],
                           servicePorts: JList[UUID],
                           qosPolicy: QosPolicy) =
        BridgePort(
            id = p.getId,
            inboundFilters = inFilters,
            outboundFilters = outFilters,
            tunnelKey = state.tunnelKey.getOrElse(p.getTunnelKey),
            peerId = if (p.hasPeerId) p.getPeerId else null,
            hostId = if (p.hasHostId) p.getHostId else null,
            previousHostId = if (p.hasPreviousHostId) p.getPreviousHostId else null,
            interfaceName = if (p.hasInterfaceName) p.getInterfaceName else null,
            adminStateUp = p.getAdminStateUp,
            portGroups = p.getPortGroupIdsList,
            isActive = state.isActive,
            vlanId = p.getVlanId.toShort,
            networkId = if (p.hasNetworkId) p.getNetworkId else null,
            preInFilterMirrors = p.getInboundMirrorIdsList,
            postOutFilterMirrors = p.getOutboundMirrorIdsList,
            postInFilterMirrors = p.getPostInFilterMirrorIdsList,
            preOutFilterMirrors = p.getPreOutFilterMirrorIdsList,
            servicePorts = servicePorts,
            qosPolicy = qosPolicy)

    private def routerPort(p: Topology.Port,
                           state: PortState,
                           inFilters: JList[UUID],
                           outFilters: JList[UUID],
                           fipNatRules: JList[UUID],
                           peeringTable: StateTable[MAC, IPv4Addr],
                           qosPolicy: QosPolicy) = {

        var ip4Addr: IPv4Addr = Port.DefaultIPv4Address
        var ip6Addr: IPv6Addr = null
        toIPAddr(p.getPortAddress) match {
            case addr: IPv4Addr => ip4Addr = addr
            case addr: IPv6Addr => ip6Addr = addr
        }
        var ip4Subnet: IPv4Subnet = Port.DefaultIPv4Subnet
        var ip6Subnet: IPv6Subnet = null

        IPSubnetUtil.fromProto(p.getPortSubnet) match {
            case subnet: IPv4Subnet => ip4Subnet = subnet
            case subnet: IPv6Subnet => ip6Subnet = subnet
        }

        RouterPort(
            id = p.getId,
            inboundFilters = inFilters,
            outboundFilters = outFilters,
            tunnelKey = state.tunnelKey.getOrElse(p.getTunnelKey),
            peerId = if (p.hasPeerId) p.getPeerId else null,
            hostId = if (p.hasHostId) p.getHostId else null,
            interfaceName = if (p.hasInterfaceName) p.getInterfaceName else null,
            adminStateUp = p.getAdminStateUp,
            portGroups = p.getPortGroupIdsList,
            isActive = state.isActive,
            containerId = if (p.hasServiceContainerId) p.getServiceContainerId else null,
            routerId = if (p.hasRouterId) p.getRouterId else null,
            portSubnetV4 = ip4Subnet,
            portAddressV4 = ip4Addr,
            portSubnetV6 = ip6Subnet,
            portAddressV6 = ip6Addr,
            portMac = if (p.hasPortMac) MAC.fromString(p.getPortMac) else null,
            routeIds = p.getRouteIdsList.asScala.map(fromProto).toSet,
            preInFilterMirrors = p.getInboundMirrorIdsList,
            postOutFilterMirrors = p.getOutboundMirrorIdsList,
            postInFilterMirrors = p.getPostInFilterMirrorIdsList,
            preOutFilterMirrors = p.getPreOutFilterMirrorIdsList,
            vni = if (p.hasVni) p.getVni else 0,
            tunnelIp = if (p.hasTunnelIp) toIPv4Addr(p.getTunnelIp) else null,
            fipNatRules = fipNatRules,
            peeringTable = peeringTable,
            qosPolicy = qosPolicy)
    }

    private def vxLanPort(p: Topology.Port,
                          state: PortState,
                          inFilters: JList[UUID],
                          outFilters: JList[UUID]) =
        VxLanPort(
            id = p.getId,
            inboundFilters = inFilters,
            outboundFilters = outFilters,
            tunnelKey = state.tunnelKey.getOrElse(p.getTunnelKey),
            peerId = if (p.hasPeerId) p.getPeerId else null,
            adminStateUp = p.getAdminStateUp,
            portGroups = p.getPortGroupIdsList,
            networkId = if (p.hasNetworkId) p.getNetworkId else null,
            vtepId = if (p.hasVtepId) p.getVtepId else null,
            preInFilterMirrors = p.getInboundMirrorIdsList,
            postOutFilterMirrors = p.getOutboundMirrorIdsList,
            postInFilterMirrors = p.getPostInFilterMirrorIdsList,
            preOutFilterMirrors = p.getPreOutFilterMirrorIdsList)

    private def servicePort(p: Topology.Port,
                            state: PortState,
                            inFilters: JList[UUID]) =
        ServicePort(
            id = p.getId,
            inboundFilters = inFilters,
            tunnelKey = state.tunnelKey.getOrElse(p.getTunnelKey),
            peerId = if (p.hasPeerId) p.getPeerId else null,
            hostId = if (p.hasHostId) p.getHostId else null,
            interfaceName = if (p.hasInterfaceName) p.getInterfaceName else null,
            realAdminStateUp = p.getAdminStateUp,
            portGroups = p.getPortGroupIdsList,
            isActive = state.isActive,
            vlanId = p.getVlanId.toShort,
            networkId = if (p.hasNetworkId) p.getNetworkId else null,
            preInFilterMirrors = p.getInboundMirrorIdsList,
            postOutFilterMirrors = p.getOutboundMirrorIdsList,
            postInFilterMirrors = p.getPostInFilterMirrorIdsList,
            preOutFilterMirrors = p.getPreOutFilterMirrorIdsList)
}

trait Port extends VirtualDevice with InAndOutFilters with MirroringDevice with Cloneable {
    def id: UUID
    def tunnelKey: Long
    def peerId: UUID
    def hostId: UUID
    def previousHostId: UUID
    def interfaceName: String
    def adminStateUp: Boolean
    def portGroups: JList[UUID] = emptyList()
    def isActive: Boolean = false
    def containerId: UUID = null
    def deviceId: UUID
    def vlanId: Short = Bridge.UntaggedVlanId
    def servicePorts: JList[UUID]
    def qosPolicy: QosPolicy = null

    val action = ToPortAction(id)

    val deviceTag = FlowTagger.tagForPort(id)
    val flowStateTag = FlowTagger.tagForFlowStateDevice(id)
    val txTag = FlowTagger.tagForPortTx(id)
    val rxTag = FlowTagger.tagForPortRx(id)

    def isExterior: Boolean = this.hostId != null && this.interfaceName != null

    def isInterior: Boolean = this.peerId != null

    def isPlugged: Boolean = this.isInterior || this.isExterior

    protected def device: ForwardingDevice

    private[this] val emit = ContinueWith(
        if (isExterior) {
            context =>
                context.calculateActionsFromMatchDiff()
                context.log.debug("Emitting packet from port {}", id)
                context.addVirtualAction(action)
                context.trackConnection(deviceId)
                AddVirtualWildcardFlow
        } else if (isInterior) {
            context =>
                tryGet(classOf[Port], peerId).ingress(context)
        } else {
            context =>
                context.log.warn("Port {} is unplugged", id)
                ErrorDrop
        })

    def ingress(context: PacketContext): SimulationResult = {
        context.log.debug(s"Packet ingressing $this")
        if (context.devicesTraversed >= Simulator.MaxDevicesTraversed) {
            context.log.debug("Dropping packet that traversed too many devices "+
                              s"(${context.devicesTraversed}}): possible " +
                              s"topology loop")
            ErrorDrop
        } else {
            context.devicesTraversed += 1
            context.addFlowTag(deviceTag)
            context.addFlowTag(rxTag)
            context.inPortId = id
            context.inPortGroups = portGroups
            context.currentDevice = deviceId
            context.nwDstRewritten = false
            mirroringPreInFilter(context, portIngress)
        }
    }

    protected def egressCommon(context: PacketContext,
                               next: SimStep): SimulationResult = {
        context.addFlowTag(deviceTag)
        context.addFlowTag(txTag)
        context.outPortId = id

        next(context)
    }

    def egress(context: PacketContext): SimulationResult = {
        if (context.devicesTraversed >= Simulator.MaxDevicesTraversed) {
            context.log.debug("Dropping packet that traversed too many devices "+
                              s"(${context.devicesTraversed}}): possible " +
                              s"topology loop")
            ErrorDrop
        } else {
            egressCommon(context, mirrorFilterAndContinueOut)
        }
    }

    private[this] val ingressDevice: SimStep = context => {
        val dev = device
        dev.continue(context, dev.process(context))
    }

    private def mirrorFilterAndContinueOut: SimStep =
        mirroringPreOutFilter(_, filterAndContinueOut)


    private val continueIn = ContinueWith(ingressDevice(_))
    private val continueOut: SimStep = c => mirroringPostOutFilter(c, emit)

    private val mirrorAndContinueIn: SimStep =
        mirroringPostInFilter(_, continueIn)
    private val filterAndContinueOut = ContinueWith(filterOut(_, continueOut))
    private val portIngress = ContinueWith(filterIn(_, mirrorAndContinueIn))

    val egressNoFilter: SimStep = context => {
        egressCommon(context, continueOut)
    }

    override protected val preIn: SimHook = c => {
        if (isExterior && (portGroups ne null))
            c.portGroups = portGroups
        c.inPortId = id
        c.inPortGroups = portGroups
        c.outPortId = null
        c.outPortGroups = null
    }

    override protected val preOut: SimHook = c => {
        c.outPortId = id
        c.outPortGroups = portGroups
    }

    override def toString =
        s"id=$id active=$isActive adminStateUp=$adminStateUp " +
        s"inboundFilters=$inboundFilters outboundFilters=$outboundFilters " +
        s"tunnelKey=$tunnelKey portGroups=$portGroups peerId=$peerId " +
        s"hostId=$hostId interfaceName=$interfaceName vlanId=$vlanId"

}

trait AbstractBridgePort extends Port {

    def networkId: UUID

    override def deviceId = networkId

    protected def device = tryGet(classOf[Bridge], networkId)

    override def egressCommon(context: PacketContext,
                              next: SimStep): SimulationResult = {
        context.log.debug(s"Packet egressing $this")
        if (id == context.inPortId) {
            Drop
        } else {
            if ((vlanId > 0) && context.wcmatch.isVlanTagged)
                context.wcmatch.removeVlanId(vlanId)
            super.egressCommon(context, next)
        }
    }

}

case class BridgePort(override val id: UUID,
                      override val inboundFilters: JList[UUID] = emptyList(),
                      override val outboundFilters: JList[UUID] = emptyList(),
                      override val tunnelKey: Long = 0,
                      override val peerId: UUID = null,
                      override val hostId: UUID = null,
                      override val previousHostId: UUID = null,
                      override val interfaceName: String = null,
                      override val adminStateUp: Boolean = true,
                      override val portGroups: JList[UUID] = emptyList(),
                      override val isActive: Boolean = false,
                      override val vlanId: Short = Bridge.UntaggedVlanId,
                      networkId: UUID,
                      override val preInFilterMirrors: JList[UUID] = emptyList(),
                      override val postOutFilterMirrors: JList[UUID] = emptyList(),
                      override val postInFilterMirrors: JList[UUID] = emptyList(),
                      override val preOutFilterMirrors: JList[UUID] = emptyList(),
                      override val servicePorts: JList[UUID] = emptyList(),
                      override val qosPolicy: QosPolicy = null)
        extends AbstractBridgePort {

    override def toString =
        s"BridgePort [${super.toString} networkId=$networkId " +
        s"qosPolicy=$qosPolicy]"

    override def ingress(context: PacketContext): SimulationResult = {
        // Only set DSCP if the packet is an IPv4 packet type, and only if
        // the QOS policy is both set for this port and has a DSCP marking rule
        if (context.wcmatch.getEtherType == IPv4.ETHERTYPE &&
            qosPolicy != null &&
            qosPolicy.dscpRules.nonEmpty) {
            // DSCP rules array can only have one member for now, although
            // filters and qualifiers might be added on later to allow for
            // multiple DSCP rules, each of which would affect a different
            // type of packet.  But for now, just get the 'head' of the array
            context.wcmatch.setNetworkTOS(qosPolicy.dscpRules.head.dscpMark)
            context.wcmatch.fieldSeen(FlowMatch.Field.NetworkTOS)
        }
        super.ingress(context)
    }

}

case class ServicePort(override val id: UUID,
                       override val inboundFilters: JList[UUID] = emptyList(),
                       override val outboundFilters: JList[UUID] = emptyList(),
                       override val tunnelKey: Long = 0,
                       override val peerId: UUID = null,
                       override val hostId: UUID = null,
                       override val previousHostId: UUID = null,
                       override val interfaceName: String = null,
                       override val adminStateUp: Boolean = true,
                       realAdminStateUp: Boolean,
                       override val portGroups: JList[UUID] = emptyList(),
                       override val isActive: Boolean = false,
                       override val vlanId: Short = Bridge.UntaggedVlanId,
                       networkId: UUID,
                       override val preInFilterMirrors: JList[UUID] = emptyList(),
                       override val postOutFilterMirrors: JList[UUID] = emptyList(),
                       override val postInFilterMirrors: JList[UUID] = emptyList(),
                       override val preOutFilterMirrors: JList[UUID] = emptyList(),
                       override val servicePorts: JList[UUID] = emptyList())
        extends AbstractBridgePort {

    override def toString =
        s"ServicePort [${super.toString} networkId=$networkId " +
        s"realAdminStateUp=$realAdminStateUp]"

    override def ingress(context: PacketContext): SimulationResult = {
        if (!realAdminStateUp) {
            context.log.debug(s"Packet ingressing $this")
            context.log.debug("Administratively down service port: dropping")
            Drop
        } else {
            super.ingress(context)
        }
    }

    override def egressCommon(context: PacketContext,
                              next: SimStep): SimulationResult = {
        context.log.debug(s"Packet egressing $this")

        if (context.isRedirectedOut) {
            val result = if (!realAdminStateUp || !isActive) {
                /* If a service port is down, what happens to the packet depends
                 * on whether the redirect has a FAIL_OPEN flag or not.
                 * - If it doesn't have the flag, the packet is simply dropped.
                 * - If it does have FAIL_OPEN set, the packet will be bounced
                 * back, as if it had arrived at the service and the service had
                 * sent it back to us. To do this, we ingress the packet on the
                 * port. The admin state and active state will be ignored on
                 * ingress, as we always report admin state as true for the
                 * port, and active is only considered on egress.
                 */
                if (context.isRedirectFailOpen) {
                    context.clearRedirect()
                    super.ingress(context)
                } else {
                    context.log.debug("Service port is down " +
                                      s"(adminStateUp:$realAdminStateUp " +
                                      s"active:$isActive): dropping")
                    Drop
                }
            } else {
                super.egressCommon(context, next)
            }
            context.clearRedirect()
            result
        } else {
            context.log.debug("Non-redirected packet egressing service port: " +
                              "dropping")
            Drop
        }
    }
}

case class RouterPort(override val id: UUID,
                      override val inboundFilters: JList[UUID] = emptyList(),
                      override val outboundFilters: JList[UUID] = emptyList(),
                      override val tunnelKey: Long = 0,
                      override val peerId: UUID = null,
                      override val hostId: UUID = null,
                      override val previousHostId: UUID = null,
                      override val interfaceName: String = null,
                      override val adminStateUp: Boolean = true,
                      override val portGroups: JList[UUID] = emptyList(),
                      override val isActive: Boolean = false,
                      override val containerId: UUID = null,
                      routerId: UUID,
                      portSubnetV4: IPv4Subnet = Port.DefaultIPv4Subnet,
                      portAddressV4: IPv4Addr = Port.DefaultIPv4Address,
                      portSubnetV6: IPv6Subnet = null,
                      portAddressV6: IPv6Addr = null,
                      portMac: MAC,
                      routeIds: Set[UUID] = Set.empty,
                      override val preInFilterMirrors: JList[UUID] = emptyList(),
                      override val postOutFilterMirrors: JList[UUID] = emptyList(),
                      override val postInFilterMirrors: JList[UUID] = emptyList(),
                      override val preOutFilterMirrors: JList[UUID] = emptyList(),
                      vni: Int = 0,
                      tunnelIp: IPv4Addr = null,
                      fipNatRules: JList[UUID] = emptyList(),
                      peeringTable: StateTable[MAC, IPv4Addr] = StateTable.empty,
                      override val qosPolicy: QosPolicy = null)
    extends Port {

    override val servicePorts: JList[UUID] = emptyList()

    def isL2 = vni != 0

    protected def device = tryGet(classOf[Router], routerId)

    override def deviceId = routerId

    override protected def reject(context: PacketContext): Unit = {
        if (context.inPortId ne null) {
            if (context.inPortId eq id)
                sendIcmpProhibited(this, context)
            else
                sendIcmpProhibited(tryGet(classOf[RouterPort], context.inPortId),
                                   context)
        }
    }

    private def sendIcmpProhibited(from: RouterPort, context: PacketContext): Unit = {
        import Icmp.IPv4Icmp._
        val ethOpt = unreachableProhibitedIcmp(from, context, context.wcmatch)
        if (ethOpt.isDefined)
            context.addGeneratedPacket(from.id, ethOpt.get)
    }

    override def egressCommon(context: PacketContext,
                              next: SimStep): SimulationResult = {
        context.log.debug(s"Packet egressing $this")
        if (context.wcmatch.getEthSrc == context.wcmatch.getEthDst) {
            ingress(context)
        } else {
            super.egressCommon(context, next)
        }
    }

    override def toString =
        s"RouterPort [${super.toString} routerId=$routerId " +
        s"portSubnet4=$portSubnetV4 portAddress4=$portAddressV4 " +
        s"portSubnet6=$portSubnetV6 portAddress6=$portAddressV6 " +
        s"portMac=$portMac " +
        s"${if (isL2) s"vni=$vni tunnelIp=$tunnelIp" else ""} " +
        s"routeIds=$routeIds fipNatRules=$fipNatRules]"
}

case class VxLanPort(override val id: UUID,
                     override val inboundFilters: JList[UUID] = emptyList(),
                     override val outboundFilters: JList[UUID] = emptyList(),
                     override val tunnelKey: Long = 0,
                     override val peerId: UUID = null,
                     override val adminStateUp: Boolean = true,
                     override val portGroups: JList[UUID] = emptyList(),
                     networkId: UUID,
                     vtepId: UUID,
                     override val preInFilterMirrors: JList[UUID] = emptyList(),
                     override val postOutFilterMirrors: JList[UUID] = emptyList(),
                     override val postInFilterMirrors: JList[UUID] = emptyList(),
                     override val preOutFilterMirrors: JList[UUID] = emptyList())
    extends Port {

    override def hostId = null
    override def previousHostId = null
    override def interfaceName = null
    override def deviceId = networkId
    override def isExterior = true
    override def isInterior = false
    override def isActive = true
    override def servicePorts: JList[UUID] = emptyList()

    protected def device = tryGet(classOf[Bridge], networkId)

    override def toString =
        s"VxLanPort [${super.toString} networkId=$networkId vtepId=$vtepId]"

}
