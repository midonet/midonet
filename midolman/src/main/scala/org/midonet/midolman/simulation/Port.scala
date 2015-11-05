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

import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, Drop, ErrorDrop, SimStep, SimulationResult}
import org.midonet.midolman.simulation.Port.NO_MIRRORS
import org.midonet.midolman.simulation.Simulator.{ContinueWith, SimHook, ToPortAction}
import org.midonet.midolman.topology.VirtualTopology.{VirtualDevice, tryGet}
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import org.midonet.sdn.flows.FlowTagger

object Port {
    import IPAddressUtil._
    import IPSubnetUtil._
    import UUIDUtil.{fromProto, fromProtoList}

    val NO_MIRRORS = new JArrayList[UUID]()

    private implicit def jlistToSSet(from: java.util.List[Commons.UUID]): Set[UUID] =
        if (from ne null) from.asScala.toSet map UUIDUtil.fromProto else Set.empty

    private def jSetToJArrayList(from: java.util.Set[UUID]): JArrayList[UUID] =
        if (from ne null) new JArrayList(from) else new JArrayList(0)

    def apply(proto: Topology.Port,
              infilters: JList[UUID],
              outfilters: JList[UUID]): Port = {
        if (proto.getSrvInsertionIdsCount > 0 && proto.hasNetworkId)
            servicePort(proto, infilters)
        else if (proto.hasVtepId)
            vxLanPort(proto, infilters, outfilters)
        else if (proto.hasNetworkId)
            bridgePort(proto, infilters, outfilters)
        else if (proto.hasRouterId)
            routerPort(proto, infilters, outfilters)
        else
            throw new ConvertException("Unknown port type")
    }

    private def bridgePort(p: Topology.Port,
                           infilters: JList[UUID],
                           outfilters: JList[UUID]) = new BridgePort(
            p.getId,
            infilters,
            outfilters,
            p.getTunnelKey,
            if (p.hasPeerId) p.getPeerId else null,
            if (p.hasHostId) p.getHostId else null,
            if (p.hasInterfaceName) p.getInterfaceName else null,
            p.getAdminStateUp,
            p.getPortGroupIdsList, false, p.getVlanId.toShort,
            if (p.hasNetworkId) p.getNetworkId else null,
            p.getInboundMirrorIdsList,
            p.getOutboundMirrorIdsList)

    private def routerPort(p: Topology.Port,
                           infilters: JList[UUID],
                           outfilters: JList[UUID]) = new RouterPort(
            p.getId,
            infilters,
            outfilters,
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
            p.getInboundMirrorIdsList,
            p.getOutboundMirrorIdsList)

    private def vxLanPort(p: Topology.Port,
                          infilters: JList[UUID],
                          outfilters: JList[UUID]) = VxLanPort(
            p.getId,
            infilters,
            outfilters,
            p.getTunnelKey,
            if (p.hasPeerId) p.getPeerId else null,
            p.getAdminStateUp,
            p.getPortGroupIdsList,
            if (p.hasNetworkId) p.getNetworkId else null,
            if (p.hasVtepId) p.getVtepId else null,
            inboundMirrors = p.getInboundMirrorIdsList,
            outboundMirrors = p.getOutboundMirrorIdsList)

    private def servicePort(p: Topology.Port,
                            infilters: JList[UUID]) =
        new ServicePort(
            p.getId,
            infilters,
            p.getTunnelKey,
            if (p.hasPeerId) p.getPeerId else null,
            if (p.hasHostId) p.getHostId else null,
            if (p.hasInterfaceName) p.getInterfaceName else null,
            p.getAdminStateUp,
            p.getPortGroupIdsList, false, p.getVlanId.toShort,
            if (p.hasNetworkId) p.getNetworkId else null,
            p.getInboundMirrorIdsList,
            p.getOutboundMirrorIdsList)

    private def filterListFrom(id: UUID): JList[UUID] = {
        val list = new JArrayList[UUID](1)
        if (id != null) {
            list.add(id)
        }
        list
    }
}

trait Port extends VirtualDevice with InAndOutFilters with MirroringDevice with Cloneable {
    def id: UUID
    def inboundFilters: JList[UUID]
    def outboundFilters: JList[UUID]
    def tunnelKey: Long
    def peerId: UUID
    def hostId: UUID
    def interfaceName: String
    def adminStateUp: Boolean
    def portGroups: JArrayList[UUID] = new JArrayList(0)
    def isActive: Boolean = false
    def deviceId: UUID
    def vlanId: Short = Bridge.UntaggedVlanId

    override def infilters = inboundFilters
    override def outfilters = outboundFilters

    val action = ToPortAction(id)

    def toggleActive(active: Boolean) = this

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
                context.log.debug("Emitting packet from vport {}", id)
                context.addVirtualAction(action)
                context.trackConnection(deviceId)
                AddVirtualWildcardFlow
        } else if (isInterior) {
            context =>
                tryGet[Port](peerId).ingress(context)
        } else {
            context =>
                context.log.warn("Port {} is unplugged", id)
                ErrorDrop
        })

    def ingress(implicit context: PacketContext): SimulationResult = {
        context.log.debug(s"Ingressing port $id")
        if (context.devicesTraversed >= Simulator.MAX_DEVICES_TRAVERSED) {
            context.log.debug(s"Dropping packet that traversed too many devices "+
                s"(${context.devicesTraversed}}), possible topology loop.")
            ErrorDrop
        } else {
            context.addFlowTag(deviceTag)
            context.addFlowTag(rxTag)
            context.inPortId = id
            mirroringInbound(context, portIngress)
        }
    }

    protected def egressCommon(context: PacketContext,
                               next: SimStep): SimulationResult = {
        context.log.debug(s"Egressing port $id")
        context.addFlowTag(deviceTag)
        context.addFlowTag(txTag)
        context.outPortId = id

        next(context)
    }

    def egress(context: PacketContext): SimulationResult = {
        egressCommon(context, filterAndContinueOut)
    }

    private[this] val ingressDevice: SimStep = context => {
        val dev = device
        dev.continue(context, dev.process(context))
    }

    protected val continueIn: SimStep = c => ingressDevice(c)
    protected val continueOut: SimStep = c => mirroringOutbound(c, emit)
    protected val filterAndContinueOut: SimStep = context => {
        filterOut(context, continueOut)
    }
    val egressNoFilter: SimStep = context => {
        egressCommon(context, continueOut)
    }

    private val portIngress = ContinueWith(filterIn(_, continueIn))

    override protected val preIn: SimHook = c => {
        if (isExterior && (portGroups ne null))
            c.portGroups = portGroups
        c.inPortId = id
        c.outPortId = null
    }

    override protected val preOut: SimHook = c => {
        c.outPortId = id
    }

    override def toString =
        s"id=$id active=$isActive adminStateUp=$adminStateUp " +
        s"inboundFilters=$inboundFilters outboundFilters=$outboundFilters " +
        s"tunnelKey=$tunnelKey portGroups=$portGroups peerId=$peerId " +
        s"hostId=$hostId interfaceName=$interfaceName vlanId=$vlanId"

}

object BridgePort {
    def random = new BridgePort(UUID.randomUUID, networkId = UUID.randomUUID)
}

class BridgePort(override val id: UUID,
                 override val inboundFilters: JList[UUID] = new JArrayList(0),
                 override val outboundFilters: JList[UUID] = new JArrayList(0),
                 override val tunnelKey: Long = 0,
                 override val peerId: UUID = null,
                 override val hostId: UUID = null,
                 override val interfaceName: String = null,
                 override val adminStateUp: Boolean = true,
                 override val portGroups: JArrayList[UUID] = new JArrayList(0),
                 override val isActive: Boolean = false,
                 override val vlanId: Short = Bridge.UntaggedVlanId,
                 val networkId: UUID,
                 override val inboundMirrors: JArrayList[UUID] = NO_MIRRORS,
                 override val outboundMirrors: JArrayList[UUID] = NO_MIRRORS)
        extends Port {
    override def toggleActive(active: Boolean) = new BridgePort(
        id, inboundFilters, outboundFilters, tunnelKey, peerId, hostId,
        interfaceName, adminStateUp, portGroups, active, vlanId,
        networkId, inboundMirrors, outboundMirrors)
    override def toString =
        s"BridgePort [${super.toString} networkId=$networkId]"

    override def deviceId = networkId

    protected def device = tryGet[Bridge](networkId)

    override def egressCommon(context: PacketContext,
                              next: SimStep): SimulationResult = {
        if (id == context.inPortId) {
            Drop
        } else {
            if ((vlanId > 0) && context.wcmatch.isVlanTagged)
                context.wcmatch.removeVlanId(vlanId)
            super.egressCommon(context, next)
        }
    }
}

class ServicePort(override val id: UUID,
                  override val inboundFilters: JList[UUID] = new JArrayList(0),
                  override val tunnelKey: Long = 0,
                  override val peerId: UUID = null,
                  override val hostId: UUID = null,
                  override val interfaceName: String = null,
                  val realAdminStateUp: Boolean,
                  override val portGroups: JArrayList[UUID] = new JArrayList(0),
                  override val isActive: Boolean = false,
                  override val vlanId: Short = Bridge.UntaggedVlanId,
                  override val networkId: UUID,
                  override val inboundMirrors: JArrayList[UUID] = NO_MIRRORS,
                  override val outboundMirrors: JArrayList[UUID] = NO_MIRRORS)
        extends BridgePort(id, inboundFilters, new JArrayList(0),
                           tunnelKey, peerId, hostId, interfaceName, true,
                           portGroups, isActive, vlanId, networkId,
                           inboundMirrors, outboundMirrors) {

    override def toggleActive(active: Boolean) = new ServicePort(
        id, inboundFilters, tunnelKey, peerId, hostId,
        interfaceName, realAdminStateUp, portGroups, active, vlanId, networkId,
        inboundMirrors, outboundMirrors)

    override def toString =
        s"ServicePort [${super.toString} networkId=$networkId" +
            s" realAdminState=$realAdminStateUp]"

    override def ingress(implicit context: PacketContext): SimulationResult = {
        if (!realAdminStateUp) {
            context.log.debug("Service port has adminStateUp=false, Dropping")
            Drop
        } else {
            super.ingress(context)
        }
    }

    override def egressCommon(context: PacketContext,
                              next: SimStep): SimulationResult = {
        if (context.isRedirectedOut()) {
            if (!realAdminStateUp || !isActive) {
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
                if (context.isRedirectFailOpen()) {
                    context.clearRedirect()
                    super.ingress(context)
                } else {
                    context.log.debug(
                        s"Service port is down (adminStateUp:$realAdminStateUp,"
                            + s" active:$isActive), Dropping")
                    Drop
                }
            } else {
                super.egressCommon(context, next)
            }
        } else {
            context.log.debug("Non-redirected packet entered service port. Dropping")
            Drop
        }
    }
}

case class RouterPort(override val id: UUID,
                      override val inboundFilters: JList[UUID] = new JArrayList(0),
                      override val outboundFilters: JList[UUID] = new JArrayList(0),
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
                      override val outboundMirrors: JList[UUID] = NO_MIRRORS)
    extends Port {

    protected def device = tryGet[Router](routerId)

    override def toggleActive(active: Boolean) = copy(isActive = active)

    override def deviceId = routerId

    override protected def reject(context: PacketContext): Unit = {
        if (context.inPortId ne null) {
            if (context.inPortId eq id)
                sendIcmpProhibited(this, context)
            else
                sendIcmpProhibited(tryGet[RouterPort](context.inPortId), context)
        }
    }

    private def sendIcmpProhibited(from: RouterPort, context: PacketContext): Unit = {
        import Icmp.IPv4Icmp._
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
                     override val inboundFilters: JList[UUID] = new JArrayList(0),
                     override val outboundFilters: JList[UUID] = new JArrayList(0),
                     override val tunnelKey: Long = 0,
                     override val peerId: UUID = null,
                     override val adminStateUp: Boolean = true,
                     override val portGroups: JArrayList[UUID] = new JArrayList(0),
                     networkId: UUID,
                     vtepId: UUID,
                     override val inboundMirrors: JList[UUID] = NO_MIRRORS,
                     override val outboundMirrors: JList[UUID] = NO_MIRRORS)
    extends Port {

    override def hostId = null
    override def interfaceName = null
    override def deviceId = networkId
    override def isExterior = true
    override def isInterior = false
    override def isActive = true

    protected def device = tryGet[Bridge](networkId)

    override def toggleActive(active: Boolean) = this

    override def toString =
        s"VxLanPort [${super.toString} networkId=$networkId vtepId=$vtepId]"
}
