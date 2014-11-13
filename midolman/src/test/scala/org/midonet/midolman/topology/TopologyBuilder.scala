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
package org.midonet.midolman.topology

import java.util.UUID

import scala.collection.JavaConverters._
import scala.util.Random

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.{IPAddress, IPVersion}
import org.midonet.cluster.models.Topology.Host.PortBinding
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology.Rule.{Action, JumpRuleData, NatTarget}
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.{UUIDUtil, IPAddressUtil}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.MACUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets._

trait TopologyBuilder {

    import org.midonet.midolman.topology.TopologyBuilder._

    protected def createBridgePort(id: UUID = UUID.randomUUID,
                                   bridgeId: Option[UUID] = None,
                                   inboundFilterId: Option[UUID] = None,
                                   outboundFilterId: Option[UUID] = None,
                                   tunnelKey: Long = -1L,
                                   peerId: Option[UUID] = None,
                                   vifId: Option[UUID] = None,
                                   hostId: Option[UUID] = None,
                                   interfaceName: Option[String] = None,
                                   adminStateUp: Boolean = false,
                                   portGroupIds: Set[UUID] = Set.empty,
                                   vlanId: Option[Int] = None): Port = {
        val builder = createPortBuilder(
            id, inboundFilterId, outboundFilterId, tunnelKey, peerId, vifId,
            hostId, interfaceName, adminStateUp, portGroupIds)
        if (bridgeId.isDefined) builder.setNetworkId(bridgeId.get.asProto)
        if (vlanId.isDefined) builder.setVlanId(vlanId.get)
        builder.build()
    }

    protected def createRouterPort(id: UUID = UUID.randomUUID,
                                   routerId: Option[UUID] = None,
                                   inboundFilterId: Option[UUID] = None,
                                   outboundFilterId: Option[UUID] = None,
                                   tunnelKey: Long = -1L,
                                   peerId: Option[UUID] = None,
                                   vifId: Option[UUID] = None,
                                   hostId: Option[UUID] = None,
                                   interfaceName: Option[String] = None,
                                   adminStateUp: Boolean = false,
                                   portGroupIds: Set[UUID] = Set.empty,
                                   portSubnet: IPSubnet[_] = randomIPv4Subnet,
                                   portAddress: IPAddr = IPv4Addr.random,
                                   portMac: MAC = MAC.random): Port = {
        val builder = createPortBuilder(
            id, inboundFilterId, outboundFilterId, tunnelKey, peerId, vifId,
            hostId, interfaceName, adminStateUp, portGroupIds)
            .setPortSubnet(portSubnet.asProto)
            .setPortAddress(portAddress.asProto)
            .setPortMac(portMac.toString)
        if (routerId.isDefined) builder.setRouterId(routerId.get.asProto)
        builder.build()
    }

    protected def createVxLanPort(id: UUID = UUID.randomUUID,
                                  bridgeId: Option[UUID] = None,
                                  inboundFilterId: Option[UUID] = None,
                                  outboundFilterId: Option[UUID] = None,
                                  tunnelKey: Long = -1L,
                                  peerId: Option[UUID] = None,
                                  vifId: Option[UUID] = None,
                                  hostId: Option[UUID] = None,
                                  interfaceName: Option[String] = None,
                                  adminStateUp: Boolean = false,
                                  portGroupIds: Set[UUID] = Set.empty,
                                  vtepMgmtIp: IPv4Addr = IPv4Addr.random)
    : Port = {
        val builder = createPortBuilder(
            id, inboundFilterId, outboundFilterId, tunnelKey, peerId, vifId,
            hostId, interfaceName, adminStateUp, portGroupIds)
            .setVtepId(UUIDUtil.toProto(0, vtepMgmtIp.toInt))
        if (bridgeId.isDefined) builder.setNetworkId(bridgeId.get.asProto)
        builder.build()
    }

    protected def createTunnelZone(id: UUID = UUID.randomUUID,
                                   tzType: TunnelZone.Type,
                                   name: Option[String] = None,
                                   hosts: Map[UUID, IPAddr] = Map.empty)
    : TunnelZone = {
        val builder = TunnelZone.newBuilder
            .setId(id.asProto)
            .setType(tzType)
            .addAllHosts(hosts.map(e => HostToIp.newBuilder
                .setHostId(e._1.asProto).setIp(e._2.asProto).build()).asJava)
        if (name.isDefined) builder.setName(name.get)
        builder.build()
    }

    protected def createHost(id: UUID = UUID.randomUUID,
                             portBindings: Map[UUID, String] = Map.empty,
                             tunnelZoneIds: Set[UUID] = Set.empty): Host = {
        Host.newBuilder
            .setId(id.asProto)
            .addAllPortBindings(
                portBindings.map(e => PortBinding.newBuilder
                    .setPortId(e._1.asProto).setInterfaceName(e._2).build())
                    .asJava)
            .addAllTunnelZoneIds(tunnelZoneIds.map(_.asProto).asJava)
            .build()
    }

    protected def createBridge(id: UUID = UUID.randomUUID,
                               tenantId: Option[String] = None,
                               name: Option[String] = None,
                               adminStateUp: Boolean = false,
                               tunnelKey: Long = -1L,
                               inboundFilterId: Option[UUID] = None,
                               outboundFilterId: Option[UUID] = None,
                               portIds: Set[UUID] = Set.empty,
                               vxlanPortIds: Set[UUID] = Set.empty): Network = {
        val builder = Network.newBuilder
            .setId(id.asProto)
            .setAdminStateUp(adminStateUp)
            .setTunnelKey(tunnelKey)
            .addAllPortIds(portIds.map(_.asProto).asJava)
            .addAllVxlanPortIds(vxlanPortIds.map(_.asProto).asJava)
        if (tenantId.isDefined) builder.setTenantId(tenantId.get)
        if (name.isDefined) builder.setName(name.get)
        if (inboundFilterId.isDefined)
            builder.setInboundFilterId(inboundFilterId.get.asProto)
        if (outboundFilterId.isDefined)
            builder.setOutboundFilterId(outboundFilterId.get.asProto)
        builder.build()
    }

    protected def createRouter(id: UUID = UUID.randomUUID,
                               tenandId: Option[String] = None,
                               name: Option[String] = None,
                               adminStateUp: Boolean = false,
                               inboundFilterId: Option[UUID] = None,
                               outboundFilterId: Option[UUID] = None,
                               loadBalancerId: Option[UUID] = None,
                               routeIds: Seq[UUID] = Seq.empty,
                               portIds: Set[UUID] = Set.empty): Router = {
        val builder = Router.newBuilder
            .setId(id.asProto)
            .setAdminStateUp(adminStateUp)
            .addAllRouteIds(routeIds.map(_.asProto).asJava)
            .addAllPortIds(portIds.map(_.asProto).asJava)
        if (tenandId.isDefined) builder.setTenantId(tenandId.get)
        if (name.isDefined) builder.setName(name.get)
        if (inboundFilterId.isDefined)
            builder.setInboundFilterId(inboundFilterId.get.asProto)
        if (outboundFilterId.isDefined)
            builder.setOutboundFilterId(outboundFilterId.get.asProto)
        if (loadBalancerId.isDefined)
            builder.setLoadBalancerId(loadBalancerId.get.asProto)
        builder.build()
    }

    protected def createRoute(id: UUID = UUID.randomUUID,
                              srcNetwork: IPSubnet[_] = randomIPv4Subnet,
                              dstNetwork: IPSubnet[_] = randomIPv4Subnet,
                              nextHop: NextHop = NextHop.BLACKHOLE,
                              nextHopPortId: UUID = UUID.randomUUID,
                              nextHopGateway: Option[String] = None,
                              weight: Option[Int] = None,
                              attributes: Option[String] = None,
                              routerId: UUID = UUID.randomUUID): Route = {
        val builder = Route.newBuilder
            .setId(id.asProto)
            .setSrcSubnet(srcNetwork.asProto)
            .setDstSubnet(dstNetwork.asProto)
            .setNextHop(nextHop)
            .setNextHopPortId(nextHopPortId.asProto)
            .setRouterId(routerId.asProto)
        if (nextHopGateway.isDefined)
            builder.setNextHopGateway(nextHopGateway.get.asProtoIPAddress)
        if (weight.isDefined) builder.setWeight(weight.get)
        if (attributes.isDefined) builder.setAttributes(attributes.get)
        builder.build()
    }

    protected def createLiteralRuleBuilder(id: UUID, chainId: UUID,
                                           action: Rule.Action)
    : Rule.Builder = {
        createRuleBuilder(id.asProto, chainId.asProto, action)
            .setType(Rule.Type.LITERAL_RULE)
    }

    protected def createJumpRuleBuilder(id: UUID, chainId: UUID,
                                        jumpChainId: UUID)
    : Rule.Builder = {
        createRuleBuilder(id.asProto, chainId.asProto, Action.JUMP)
            .setType(Rule.Type.JUMP_RULE)
            .setJumpRuleData(JumpRuleData.newBuilder
                                 .setJumpTo(jumpChainId.asProto)
                                 .build())
    }

    private def createRuleBuilder(id: Commons.UUID, chainId: Commons.UUID,
                                  action: Rule.Action)
    : Rule.Builder = {
        Rule.newBuilder
            .setId(id)
            .setChainId(chainId)
            .setAction(action)
    }

    protected def createChainBuilder(id: UUID, name: Option[String],
                                     ruleIds: Seq[UUID])
    : Chain.Builder = {
        val builder = Chain.newBuilder
            .setId(id.asProto)
            .addAllRuleIds(ruleIds.map(_.asProto).asJava)

        if (name.isDefined)
            builder.setName(name.get)

        builder
    }

    protected def createIPSubnetBuilder(version: IPVersion, prefix: String,
                                        prefixLength: Int): Commons.IPSubnet.Builder = {
        Commons.IPSubnet.newBuilder
            .setVersion(version)
            .setAddress(prefix)
            .setPrefixLength(prefixLength)
    }

    protected def createNatTargetBuilder(startAddr: IPAddress =
                                             IPAddressUtil.toProto("192.168.0.1"),
                                         endAddr: IPAddress =
                                             IPAddressUtil.toProto("192.168.0.254"),
                                         portStart: Int, portEnd: Int)
    : NatTarget.Builder = {
        NatTarget.newBuilder
            .setNwStart(startAddr)
            .setNwEnd(endAddr)
            .setTpStart(portStart)
            .setTpEnd(portEnd)
    }

    private def createPortBuilder(id: UUID,
                                  inboundFilterId: Option[UUID],
                                  outboundFilterId: Option[UUID],
                                  tunnelKey: Long,
                                  peerId: Option[UUID],
                                  vifId: Option[UUID],
                                  hostId: Option[UUID],
                                  interfaceName: Option[String],
                                  adminStateUp: Boolean,
                                  portGroupIds: Set[UUID]): Port.Builder = {
        val builder = Port.newBuilder
            .setId(id.asProto)
            .setTunnelKey(tunnelKey)
            .setAdminStateUp(adminStateUp)
            .addAllPortGroupIds(portGroupIds.map(_.asProto).asJava)

        if (inboundFilterId.isDefined)
            builder.setInboundFilterId(inboundFilterId.get.asProto)
        if (outboundFilterId.isDefined)
            builder.setOutboundFilterId(outboundFilterId.get.asProto)
        if (peerId.isDefined)
            builder.setPeerId(peerId.get.asProto)
        if (vifId.isDefined)
            builder.setVifId(vifId.get.asProto)
        if (hostId.isDefined)
            builder.setHostId(hostId.get.asProto)
        if (interfaceName.isDefined)
            builder.setInterfaceName(interfaceName.get)
        builder
    }

}

object TopologyBuilder {

    class RichPort(port: Port) {
        def setBridgeId(bridgeId: UUID): Port =
            port.toBuilder.setNetworkId(bridgeId.asProto).build()
        def setRouterId(routerId: UUID): Port =
            port.toBuilder.setRouterId(routerId.asProto).build()
        def setInboundFilterId(filterId: UUID): Port =
            port.toBuilder.setInboundFilterId(filterId.asProto).build()
        def setOutboundFilterId(filterId: UUID): Port =
            port.toBuilder.setOutboundFilterId(filterId.asProto).build()
        def setTunnelKey(tunnelKey: Long): Port =
            port.toBuilder.setTunnelKey(tunnelKey).build()
        def setPeerId(peerId: UUID): Port =
            port.toBuilder.setPeerId(peerId.asProto).build()
        def setVifId(vifId: UUID): Port =
            port.toBuilder.setVifId(vifId.asProto).build()
        def setInterfaceName(name: String): Port =
            port.toBuilder.setInterfaceName(name).build()
        def setAdminStateUp(adminStateUp: Boolean): Port =
            port.toBuilder.setAdminStateUp(adminStateUp).build()
        def setVlanId(vlanId: Int): Port =
            port.toBuilder.setVlanId(vlanId).build()
        def setPortSubnet(ipSubnet: IPSubnet[_]): Port =
            port.toBuilder.setPortSubnet(ipSubnet.asProto).build()
        def setPortAddress(ipAddress: IPAddr): Port =
            port.toBuilder.setPortAddress(ipAddress.asProto).build()
        def setPortMac(mac: MAC): Port =
            port.toBuilder.setPortMac(mac.toString).build()
        def clearBridgeId(): Port =
            port.toBuilder.clearNetworkId().build()
        def clearRouterId(): Port =
            port.toBuilder.clearRouterId().build()
        def clearInboundFilterId(): Port =
            port.toBuilder.clearInboundFilterId().build()
        def clearOutboundFilterId(): Port =
            port.toBuilder.clearOutboundFilterId().build()
        def clearPeerId(): Port =
            port.toBuilder.clearPeerId().build()
        def clearVifId(): Port =
            port.toBuilder.clearVifId().build()
        def clearInterfaceName: Port =
            port.toBuilder.clearInterfaceName().build()
        def clearVlanId(): Port =
            port.toBuilder.clearVlanId().build()
        def clearPortSubnet(ipSubnet: IPSubnet[_]): Port =
            port.toBuilder.clearPortSubnet().build()
        def clearPortAddress(ipAddress: IPAddr): Port =
            port.toBuilder.clearPortAddress().build()
        def clearPortMac(mac: MAC): Port =
            port.toBuilder.clearPortMac().build()
    }

    private val random = new Random()

    def randomIPv4Subnet = new IPv4Subnet(random.nextInt(), random.nextInt(32))

    implicit def asRichPort(port: Port): RichPort = new RichPort(port)

}
