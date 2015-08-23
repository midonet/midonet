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
package org.midonet.cluster.topology

import java.util.UUID

import scala.collection.JavaConverters._
import scala.util.Random

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Commons.{Condition, IPAddress, LBStatus}
import org.midonet.cluster.models.Topology.HealthMonitor.HealthMonitorType
import org.midonet.cluster.models.Topology.IPAddrGroup.IPAddrPorts
import org.midonet.cluster.models.Topology.Pool.{PoolLBMethod, PoolProtocol}
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology.Rule.{Action, JumpRuleData, NatRuleData, NatTarget}
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.models.Topology.Vip.SessionPersistence
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPSubnetUtil, RangeUtil, UUIDUtil}
import org.midonet.midolman.rules.FragmentPolicy
import org.midonet.midolman.state.l4lb.{LBStatus => L4LBStatus}
import org.midonet.midolman.{layer3 => l3}
import org.midonet.packets.{IPAddr, IPSubnet, IPv4Addr, IPv4Subnet, MAC}
import org.midonet.util.Range

trait TopologyBuilder {

    import TopologyBuilder._

    def createBridgePort(id: UUID = UUID.randomUUID,
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

    def createRouterPort(id: UUID = UUID.randomUUID,
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
                         portMac: MAC = MAC.random,
                         bgpId: Option[UUID] = None,
                         routeIds: Set[UUID] = Set.empty): Port = {
        val builder = createPortBuilder(
            id, inboundFilterId, outboundFilterId, tunnelKey, peerId, vifId,
            hostId, interfaceName, adminStateUp, portGroupIds)
            .setPortSubnet(portSubnet.asProto)
            .setPortAddress(portAddress.asProto)
            .setPortMac(portMac.toString)
            .addAllRouteIds(routeIds.map(_.asProto).asJava)
        if (routerId.isDefined) builder.setRouterId(routerId.get.asProto)
        builder.build()
    }

    def createVxLanPort(id: UUID = UUID.randomUUID,
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
                        vtepMgmtIp: IPv4Addr = IPv4Addr.random): Port = {
        val builder = createPortBuilder(
            id, inboundFilterId, outboundFilterId, tunnelKey, peerId, vifId,
            hostId, interfaceName, adminStateUp, portGroupIds)
            .setVtepId(UUIDUtil.toProto(0, vtepMgmtIp.toInt))
        if (bridgeId.isDefined) builder.setNetworkId(bridgeId.get.asProto)
        builder.build()
    }

    def createTunnelZone(id: UUID = UUID.randomUUID,
                         tzType: TunnelZone.Type,
                         name: Option[String] = None,
                         hosts: Map[UUID, IPAddr] = Map.empty): TunnelZone = {
        val builder = TunnelZone.newBuilder
            .setId(id.asProto)
            .setType(tzType)
            .addAllHosts(hosts.map(e => HostToIp.newBuilder
                .setHostId(e._1.asProto).setIp(e._2.asProto).build()).asJava)
        if (name.isDefined) builder.setName(name.get)
        builder.build()
    }

    def createHost(id: UUID = UUID.randomUUID,
                   portIds: Set[UUID] = Set.empty,
                   tunnelZoneIds: Set[UUID] = Set.empty): Host = {
        Host.newBuilder
            .setId(id.asProto)
            .addAllPortIds(portIds.map(_.asProto).asJava)
            .addAllTunnelZoneIds(tunnelZoneIds.map(_.asProto).asJava)
            .build()
    }

    def createBridge(id: UUID = UUID.randomUUID,
                     tenantId: Option[String] = None,
                     name: Option[String] = None,
                     adminStateUp: Boolean = false,
                     tunnelKey: Long = -1L,
                     inboundFilterId: Option[UUID] = None,
                     outboundFilterId: Option[UUID] = None,
                     portIds: Set[UUID] = Set.empty,
                     vxlanPortIds: Set[UUID] = Set.empty,
                     dhcpIds: Seq[UUID] = Seq.empty): Network = {
        val builder = Network.newBuilder
            .setId(id.asProto)
            .setAdminStateUp(adminStateUp)
            .setTunnelKey(tunnelKey)
            .addAllPortIds(portIds.map(_.asProto).asJava)
            .addAllVxlanPortIds(vxlanPortIds.map(_.asProto).asJava)
            .addAllDhcpIds(dhcpIds.map(_.asProto).asJava)
        if (tenantId.isDefined) builder.setTenantId(tenantId.get)
        if (name.isDefined) builder.setName(name.get)
        if (inboundFilterId.isDefined)
            builder.setInboundFilterId(inboundFilterId.get.asProto)
        if (outboundFilterId.isDefined)
            builder.setOutboundFilterId(outboundFilterId.get.asProto)
        builder.build()
    }

    def createDhcp(networkId: UUID,
                   id: UUID = UUID.randomUUID,
                   defaultGw: IPAddr = IPv4Addr.random,
                   serverAddr: IPAddr = IPv4Addr.random,
                   subnetAddr: IPSubnet[_] = IPv4Addr.random.subnet(24),
                   dns: List[IPv4Addr] = List(),
                   opt121routes: List[Dhcp.Opt121Route] = List(),
                   enabled: Boolean = true,
                   mtu: Int = 1024): Dhcp = {
        val builder = Dhcp.newBuilder()
            .setId(id.asProto)
            .setNetworkId(networkId.asProto)
            .setDefaultGateway(defaultGw.asProto)
            .setEnabled(enabled)
            .setInterfaceMtu(mtu)
            .setSubnetAddress(subnetAddr.asProto)
            .setServerAddress(serverAddr.asProto)
            .addAllDnsServerAddress(dns.map(_.asProto).asJava)
            .addAllOpt121Routes(opt121routes.asJavaCollection)
        builder.build()
    }

    def createDhcpHost(name: String, mac: MAC, ip: IPAddr): Dhcp.Host = {
        val builder = Dhcp.Host.newBuilder()
                               .setName(name)
                               .setMac(mac.toString)
                               .setIpAddress(ip.asProto)
        builder.build()
    }

    def createRouter(id: UUID = UUID.randomUUID,
                     tenandId: Option[String] = None,
                     name: Option[String] = None,
                     adminStateUp: Boolean = false,
                     inboundFilterId: Option[UUID] = None,
                     outboundFilterId: Option[UUID] = None,
                     loadBalancerId: Option[UUID] = None,
                     asNumber: Option[Int] = None,
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
        if (asNumber.isDefined)
            builder.setAsNumber(asNumber.get)
        builder.build()
    }

    def createRoute(id: UUID = UUID.randomUUID,
                    srcNetwork: IPSubnet[_] = randomIPv4Subnet,
                    dstNetwork: IPSubnet[_] = randomIPv4Subnet,
                    nextHop: NextHop = NextHop.BLACKHOLE,
                    nextHopPortId: Option[UUID] = None,
                    nextHopGateway: Option[String] = None,
                    weight: Option[Int] = None,
                    attributes: Option[String] = None,
                    routerId: Option[UUID] = None): Route = {
        val builder = Route.newBuilder
            .setId(id.asProto)
            .setSrcSubnet(srcNetwork.asProto)
            .setDstSubnet(dstNetwork.asProto)
            .setNextHop(nextHop)
        if (nextHopPortId.isDefined)
            builder.setNextHopPortId(nextHopPortId.get.asProto)
        if (nextHopGateway.isDefined)
            builder.setNextHopGateway(nextHopGateway.get.asProtoIPAddress)
        if (weight.isDefined) builder.setWeight(weight.get)
        if (attributes.isDefined) builder.setAttributes(attributes.get)
        if (routerId.isDefined) builder.setRouterId(routerId.get.asProto)
        builder.build()
    }

    def setCondition(ruleBuilder: Rule.Builder,
                     conjunctionInv: Option[Boolean] = None,
                     matchForwardFlow: Option[Boolean] = None,
                     matchReturnFlow: Option[Boolean] = None,
                     inPortIds: Option[Set[UUID]] = None,
                     inPortInv: Option[Boolean] = None,
                     outPortIds: Option[Set[UUID]] = None,
                     outPortInv: Option[Boolean] = None,
                     portGroup: Option[UUID] = None,
                     invPortGroup: Option[Boolean] = None,
                     ipAddrGroupIdSrc: Option[UUID] = None,
                     invIpAddrGroupIdSrc: Option[Boolean] = None,
                     ipAddrGroupIdDst: Option[UUID] = None,
                     invIpAddrGroupIdDst: Option[Boolean] = None,
                     etherType: Option[Int] = None,
                     invDlType: Option[Boolean] = None,
                     ethSrc: Option[MAC] = None,
                     ethSrcMask: Option[Long] = None,
                     invDlSrc: Option[Boolean] = None,
                     ethDst: Option[MAC] = None,
                     dlDstMask: Option[Long] = None,
                     invDlDst: Option[Boolean] = None,
                     nwTos: Option[Byte] = None,
                     nwTosInv: Option[Boolean] = None,
                     nwProto: Option[Byte] = None,
                     nwProtoInv: Option[Boolean] = None,
                     nwSrcIp: Option[IPSubnet[_]] = None,
                     nwDstIp: Option[IPSubnet[_]] = None,
                     tpSrc: Option[Range[Integer]] = None,
                     tpDst: Option[Range[Integer]] = None,
                     nwSrcInv: Option[Boolean] = None,
                     nwDstInv: Option[Boolean] = None,
                     tpSrcInv: Option[Boolean] = None,
                     tpDstInv: Option[Boolean] = None,
                     traversedDevice: Option[UUID] = None,
                     traversedDeviceInv: Option[Boolean] = None,
                     fragmentPolicy: Option[FragmentPolicy] = None)
    : Rule.Builder = {
        val builder = ruleBuilder.getConditionBuilder

        setConditionParameters(builder, conjunctionInv, matchForwardFlow,
                               matchReturnFlow, inPortIds, inPortInv,
                               outPortIds, outPortInv, portGroup,
                               invPortGroup, ipAddrGroupIdSrc,
                               invIpAddrGroupIdSrc, ipAddrGroupIdDst,
                               invIpAddrGroupIdDst, etherType,
                               invDlType, ethSrc, ethSrcMask, invDlSrc,
                               ethDst, dlDstMask, invDlDst,
                               nwTos, nwTosInv, nwProto,
                               nwProtoInv, nwSrcIp, nwDstIp,
                               tpSrc, tpDst, nwSrcInv, nwDstInv,
                               tpSrcInv, tpDstInv, traversedDevice,
                               traversedDeviceInv, fragmentPolicy)
        ruleBuilder

    }

    protected def setConditionParameters(builder: Condition.Builder,
                                         conjunctionInv: Option[Boolean] = None,
                                         matchForwardFlow: Option[Boolean] = None,
                                         matchReturnFlow: Option[Boolean] = None,
                                         inPortIds: Option[Set[UUID]] = None,
                                         inPortInv: Option[Boolean] = None,
                                         outPortIds: Option[Set[UUID]] = None,
                                         outPortInv: Option[Boolean] = None,
                                         portGroup: Option[UUID] = None,
                                         invPortGroup: Option[Boolean] = None,
                                         ipAddrGroupIdSrc: Option[UUID] = None,
                                         invIpAddrGroupIdSrc: Option[Boolean] = None,
                                         ipAddrGroupIdDst: Option[UUID] = None,
                                         invIpAddrGroupIdDst: Option[Boolean] = None,
                                         etherType: Option[Int] = None,
                                         invDlType: Option[Boolean] = None,
                                         ethSrc: Option[MAC] = None,
                                         ethSrcMask: Option[Long] = None,
                                         invDlSrc: Option[Boolean] = None,
                                         ethDst: Option[MAC] = None,
                                         dlDstMask: Option[Long] = None,
                                         invDlDst: Option[Boolean] = None,
                                         nwTos: Option[Byte] = None,
                                         nwTosInv: Option[Boolean] = None,
                                         nwProto: Option[Byte] = None,
                                         nwProtoInv: Option[Boolean] = None,
                                         nwSrcIp: Option[IPSubnet[_]] = None,
                                         nwDstIp: Option[IPSubnet[_]] = None,
                                         tpSrc: Option[Range[Integer]] = None,
                                         tpDst: Option[Range[Integer]] = None,
                                         nwSrcInv: Option[Boolean] = None,
                                         nwDstInv: Option[Boolean] = None,
                                         tpSrcInv: Option[Boolean] = None,
                                         tpDstInv: Option[Boolean] = None,
                                         traversedDevice: Option[UUID] = None,
                                         traversedDeviceInv: Option[Boolean] = None,
                                         fragmentPolicy: Option[FragmentPolicy] = None)
            : Condition.Builder = {
        if (matchForwardFlow.isDefined) {
            builder.setMatchForwardFlow(matchForwardFlow.get)
        }
        if (matchReturnFlow.isDefined) {
            builder.setMatchReturnFlow(matchReturnFlow.get)
        }
        if (conjunctionInv.isDefined)
            builder.setConjunctionInv(conjunctionInv.get)
        if (inPortIds.isDefined)
            builder.addAllInPortIds(inPortIds.get.map(_.asProto).asJava)
        if (inPortInv.isDefined)
            builder.setInPortInv(inPortInv.get)
        if (outPortIds.isDefined)
            builder.addAllOutPortIds(outPortIds.get.map(_.asProto).asJava)
        if (outPortInv.isDefined)
            builder.setOutPortInv(outPortInv.get)
        if (portGroup.isDefined)
            builder.setPortGroupId(portGroup.get.asProto)
        if (invPortGroup.isDefined)
            builder.setInvPortGroup(invPortGroup.get)
        if (ipAddrGroupIdSrc.isDefined)
            builder.setIpAddrGroupIdSrc(ipAddrGroupIdSrc.get.asProto)
        if (invIpAddrGroupIdSrc.isDefined)
            builder.setInvIpAddrGroupIdSrc(invIpAddrGroupIdSrc.get)
        if (ipAddrGroupIdDst.isDefined)
            builder.setIpAddrGroupIdDst(ipAddrGroupIdDst.get.asProto)
        if (invIpAddrGroupIdDst.isDefined)
            builder.setInvIpAddrGroupIdDst(invIpAddrGroupIdDst.get)
        if (etherType.isDefined)
            builder.setDlType(etherType.get)
        if (invDlType.isDefined)
            builder.setInvDlType(invDlType.get)
        if (ethSrc.isDefined)
            builder.setDlSrc(ethSrc.get.toString)
        if (ethSrcMask.isDefined)
            builder.setDlSrcMask(ethSrcMask.get)
        if (invDlSrc.isDefined)
            builder.setInvDlSrc(invDlSrc.get)
        if (ethDst.isDefined)
            builder.setDlDst(ethDst.get.toString)
        if (dlDstMask.isDefined)
            builder.setDlDstMask(dlDstMask.get)
        if (invDlDst.isDefined)
            builder.setInvDlDst(invDlDst.get)
        if (nwTos.isDefined)
            builder.setNwTos(nwTos.get)
        if (nwTosInv.isDefined)
            builder.setNwTosInv(nwTosInv.get)
        if (nwProto.isDefined)
            builder.setNwProto(nwProto.get)
        if (nwProtoInv.isDefined)
            builder.setNwProtoInv(nwProtoInv.get)
        if (nwSrcIp.isDefined)
            builder.setNwSrcIp(IPSubnetUtil.toProto(nwSrcIp.get))
        if (nwDstIp.isDefined)
            builder.setNwDstIp(IPSubnetUtil.toProto(nwDstIp.get))
        if (tpSrc.isDefined)
            builder.setTpSrc(RangeUtil.toProto(tpSrc.get))
        if (tpDst.isDefined)
            builder.setTpDst(RangeUtil.toProto(tpDst.get))
        if (nwSrcInv.isDefined)
            builder.setNwSrcInv(nwSrcInv.get)
        if (nwDstInv.isDefined)
            builder.setNwDstInv(nwDstInv.get)
        if (tpSrcInv.isDefined)
            builder.setTpSrcInv(tpSrcInv.get)
        if (tpDstInv.isDefined)
            builder.setTpDstInv(tpDstInv.get)
        if (traversedDevice.isDefined)
            builder.setTraversedDevice(traversedDevice.get.asProto)
        if (traversedDeviceInv.isDefined)
            builder.setTraversedDeviceInv(traversedDeviceInv.get)
        if (fragmentPolicy.isDefined)
            builder.setFragmentPolicy(
                Condition.FragmentPolicy.valueOf(fragmentPolicy.get.name))
        builder
    }

    private def createRuleBuilder(id: UUID,
                                  chainId: Option[UUID],
                                  action: Option[Rule.Action]): Rule.Builder = {
        val builder = Rule.newBuilder.setId(id.asProto)
        if (chainId.isDefined)
            builder.setChainId(chainId.get.asProto)
        if (action.isDefined)
            builder.setAction(action.get)

        builder
    }

    def createLiteralRuleBuilder(id: UUID = UUID.randomUUID(),
                                 chainId: Option[UUID] = None,
                                 action: Option[Rule.Action] = None)
    : Rule.Builder = {
        createRuleBuilder(id, chainId, action)
            .setType(Rule.Type.LITERAL_RULE)
    }

    def createTraceRuleBuilder(id: UUID,
                               chainId: Option[UUID] = None): Rule.Builder = {
        createRuleBuilder(id, chainId, Option(Action.CONTINUE))
            .setType(Rule.Type.TRACE_RULE)
    }

    def createJumpRuleBuilder(id: UUID,
                              chainId: Option[UUID] = None,
                              jumpChainId: Option[UUID] = None)
    : Rule.Builder = {
        val builder = createRuleBuilder(id, chainId, Option(Action.JUMP))
            .setType(Rule.Type.JUMP_RULE)

        if (jumpChainId.isDefined)
            builder.setJumpRuleData(JumpRuleData.newBuilder
                                        .setJumpChainId(jumpChainId.get.asProto)
                                        .build())
        builder
    }

    def createNatTarget(startAddr: IPAddress = IPv4Addr.random.asProto,
                        endAddr: IPAddress = IPv4Addr.random.asProto,
                        portStart: Int = random.nextInt(),
                        portEnd: Int = random.nextInt()): NatTarget = {
        NatTarget.newBuilder
            .setNwStart(startAddr)
            .setNwEnd(endAddr)
            .setTpStart(portStart)
            .setTpEnd(portEnd)
            .build()
    }

    protected def createRedirectRuleBuilder(id: UUID = UUID.randomUUID(),
                                        chainId: Option[UUID] = None,
                                        targetPortId: org.midonet.cluster.models.Commons.UUID,
                                        ingress: Boolean = false,
                                        failOpen: Boolean = false)
    : Rule.Builder = {
        val builder = createRuleBuilder(id, chainId, Option(Action.REDIRECT))
            .setType(Rule.Type.REDIRECT_RULE)

        builder.setRedirRuleData(Rule.RedirRuleData.newBuilder
                                    .setTargetPort(targetPortId)
                                    .setIngress(ingress)
                                    .setFailOpen(failOpen)
                                    .build())
        builder
    }

    def createNatRuleBuilder(id: UUID,
                             chainId: Option[UUID] = None,
                             dnat: Option[Boolean] = None,
                             matchFwdFlow: Option[Boolean] = None,
                             targets: Set[NatTarget] = Set.empty,
                             reverse: Boolean = false)
    : Rule.Builder = {
        val builder = createRuleBuilder(id, chainId, Option(Action.CONTINUE))
            .setType(Rule.Type.NAT_RULE)
        val ruleData = NatRuleData.newBuilder
        if (reverse) {
            ruleData.setReverse(true)
        } else {
            ruleData.addAllNatTargets(targets.asJava)
        }

        if (dnat.isDefined)
            ruleData.setDnat(dnat.get)

        builder.setNatRuleData(ruleData.build())

        if (matchFwdFlow.isDefined) {
            builder.getConditionBuilder
                .setMatchForwardFlow(matchFwdFlow.get)
                .setMatchReturnFlow(!matchFwdFlow.get)
        }
        builder
    }

    def createChain(id: UUID = UUID.randomUUID,
                    name: Option[String] = None,
                    ruleIds: Set[UUID] = Set.empty): Chain = {
        val builder = Chain.newBuilder
            .setId(id.asProto)
            .addAllRuleIds(ruleIds.map(_.asProto).asJava)

        if (name.isDefined) builder.setName(name.get)

        builder.build()
    }

    def createPortGroup(id: UUID = UUID.randomUUID,
                        name: Option[String] = None,
                        tenantId: Option[String] = None,
                        stateful: Option[Boolean] = None,
                        portIds: Seq[UUID] = Seq.empty): PortGroup = {
        val builder = PortGroup.newBuilder
            .setId(id.asProto)
            .addAllPortIds(portIds.map(_.asProto).asJava)
        if (name.isDefined) builder.setName(name.get)
        if (tenantId.isDefined) builder.setTenantId(tenantId.get)
        if (stateful.isDefined) builder.setStateful(stateful.get)
        builder.build()
    }

    def createVip(id: UUID = UUID.randomUUID,
                  adminStateUp: Option[Boolean] = None,
                  poolId: Option[UUID] = None,
                  address: Option[IPAddr] = None,
                  protocolPort: Option[Int] = None,
                  sessionPersistence: Option[Vip.SessionPersistence] = None) = {
        val builder = Vip.newBuilder
            .setId(id.asProto)
        if (adminStateUp.isDefined)
            builder.setAdminStateUp(adminStateUp.get)
        if (poolId.isDefined)
            builder.setPoolId(poolId.get.asProto)
        if (address.isDefined)
            builder.setAddress(address.get.asProto)
        if (protocolPort.isDefined)
            builder.setProtocolPort(protocolPort.get)
        if (sessionPersistence.isDefined) {
            builder.setSessionPersistence(sessionPersistence.get)
        }

        builder.build()
    }

    def createLoadBalancer(id: UUID = UUID.randomUUID,
                           adminStateUp: Option[Boolean] = None,
                           routerId: Option[UUID] = None,
                           poolIds: Set[UUID] = Set.empty) = {
        val builder = LoadBalancer.newBuilder
            .setId(id.asProto)
        if (adminStateUp.isDefined)
            builder.setAdminStateUp(adminStateUp.get)
        if (routerId.isDefined)
            builder.setRouterId(routerId.get.asProto)
        builder.addAllPoolIds(poolIds.map(_.asProto).asJava)
            .build()
    }

    def createIpAddrGroup(id: UUID = UUID.randomUUID,
                          name: Option[String] = None,
                          inChainId: Option[UUID] = None,
                          outChainId: Option[UUID] = None,
                          ruleIds: Set[UUID] = Set.empty)
    : IPAddrGroup = {
        val builder = IPAddrGroup.newBuilder
            .setId(id.asProto)
            .addAllRuleIds(ruleIds.map(_.asProto).asJava)
        if (name.isDefined)
            builder.setName(name.get)
        if (inChainId.isDefined)
            builder.setInboundChainId(inChainId.get.asProto)
        if (outChainId.isDefined)
            builder.setOutboundChainId(outChainId.get.asProto)
        builder.build()
    }

    def createPool(id: UUID = UUID.randomUUID,
                   healthMonitorId: Option[UUID] = None,
                   loadBalancerId: Option[UUID] = None,
                   adminStateUp: Option[Boolean] = None,
                   protocol: Option[PoolProtocol] = None,
                   lbMethod: Option[PoolLBMethod] = None,
                   poolMemberIds: Set[UUID] = Set.empty): Pool = {
        val builder = Pool.newBuilder
            .setId(id.asProto)
            .addAllPoolMemberIds(poolMemberIds.map(_.asProto).asJava)
        if (healthMonitorId.isDefined)
            builder.setHealthMonitorId(healthMonitorId.get.asProto)
        if (loadBalancerId.isDefined)
            builder.setLoadBalancerId(loadBalancerId.get.asProto)
        if (adminStateUp.isDefined)
            builder.setAdminStateUp(adminStateUp.get)
        if (protocol.isDefined) builder.setProtocol(protocol.get)
        if (lbMethod.isDefined) builder.setLbMethod(lbMethod.get)
        builder.build()
    }

    def createPoolMember(id: UUID = UUID.randomUUID,
                         adminStateUp: Option[Boolean] = None,
                         poolId: Option[UUID] = None,
                         status: Option[LBStatus] = None,
                         address: Option[IPAddr] = None,
                         protocolPort: Option[Int] = None,
                         weight: Option[Int] = None): PoolMember = {
        val builder = PoolMember.newBuilder
            .setId(id.asProto)
        if (adminStateUp.isDefined)
            builder.setAdminStateUp(adminStateUp.get)
        if (poolId.isDefined)
            builder.setPoolId(poolId.get.asProto)
        if (status.isDefined)
            builder.setStatus(status.get)
        if (address.isDefined)
            builder.setAddress(address.get.asProto)
        if (protocolPort.isDefined)
            builder.setProtocolPort(protocolPort.get)
        if (weight.isDefined)
            builder.setWeight(weight.get)
        builder.build()
    }

    def createBgpNetwork(id: UUID = UUID.randomUUID,
                         subnet: Option[IPSubnet[_]] = None,
                         routerId: Option[UUID] = None)
    : BgpNetwork = {
        val builder = BgpNetwork.newBuilder().setId(id.asProto)
        if (subnet.isDefined)
            builder.setSubnet(subnet.get.asProto)
        if (routerId.isDefined)
            builder.setRouterId(routerId.get.asProto)
        builder.build()
    }

    def createBgpPeer(id: UUID = UUID.randomUUID,
                                asNumber: Option[Int] = None,
                                address: Option[IPAddr] = None,
                                routerId: Option[UUID] = None): BgpPeer = {
        val builder = BgpPeer.newBuilder().setId(id.asProto)
        if (asNumber.isDefined)
            builder.setAsNumber(asNumber.get)
        if (address.isDefined)
            builder.setAddress(address.get.asProto)
        if (routerId.isDefined)
            builder.setRouterId(routerId.get.asProto)
        builder.build()
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

    def createHealthMonitor(id: UUID = UUID.randomUUID(),
                            adminStateUp: Boolean = false,
                            healthMonitorType: Option[HealthMonitorType] = None,
                            status: Option[LBStatus] = None,
                            delay: Option[Int] = None,
                            timeout: Option[Int] = None,
                            maxRetries: Option[Int] = None) : HealthMonitor = {
        val builder = HealthMonitor.newBuilder()
            .setId(id.asProto).setAdminStateUp(adminStateUp)
        if (healthMonitorType.isDefined)
            builder.setType(healthMonitorType.get)
        if (status.isDefined)
            builder.setStatus(status.get)
        if (delay.isDefined)
            builder.setDelay(delay.get)
        if (timeout.isDefined)
            builder.setTimeout(timeout.get)
        if (maxRetries.isDefined)
            builder.setMaxRetries(maxRetries.get)
        builder.build()
    }
}

object TopologyBuilder {

    final class RichPort(val port: Port) extends AnyVal {
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
        def setHostId(hostId: UUID): Port =
            port.toBuilder.setHostId(hostId.asProto).build()
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
        def clearHostId(): Port =
            port.toBuilder.clearHostId().build()
        def clearPeerId(): Port =
            port.toBuilder.clearPeerId().build()
        def clearVifId(): Port =
            port.toBuilder.clearVifId().build()
        def clearInterfaceName(): Port =
            port.toBuilder.clearInterfaceName().build()
        def clearVlanId(): Port =
            port.toBuilder.clearVlanId().build()
        def clearPortSubnet(): Port =
            port.toBuilder.clearPortSubnet().build()
        def clearPortAddress(): Port =
            port.toBuilder.clearPortAddress().build()
        def clearPortMac(): Port =
            port.toBuilder.clearPortMac().build()
        def addRouteId(routeId: UUID): Port =
            port.toBuilder.addRouteIds(routeId.asProto).build()
    }

    final class RichBridge(val bridge: Network) extends AnyVal {
        def setName(name: String): Network =
            bridge.toBuilder.setName(name).build()
        def setTenantId(tenantId: String): Network =
            bridge.toBuilder.setTenantId(tenantId).build()
        def setAdminStateUp(adminStateUp: Boolean): Network =
            bridge.toBuilder.setAdminStateUp(adminStateUp).build()
        def setTunnelKey(tunnelKey: Long): Network =
            bridge.toBuilder.setTunnelKey(tunnelKey).build()
        def setInboundFilterId(filterId: UUID): Network =
            bridge.toBuilder.setInboundFilterId(filterId.asProto).build()
        def setOutboundFilterId(filterId: UUID): Network =
            bridge.toBuilder.setOutboundFilterId(filterId.asProto).build()
        def setVni(vni: Int): Network =
            bridge.toBuilder.setVni(vni).build()
        def clearName(): Network =
            bridge.toBuilder.clearName().build()
        def clearTenantId(): Network =
            bridge.toBuilder.clearTenantId().build()
        def clearTunnelKey(): Network =
            bridge.toBuilder.clearTunnelKey().build()
        def clearInboundFilterId(): Network =
            bridge.toBuilder.clearInboundFilterId().build()
        def clearOutboundFilterId(): Network =
            bridge.toBuilder.clearOutboundFilterId().build()
        def clearVni(): Network =
            bridge.toBuilder.clearVni().build()
    }

    final class RichRouter(val router: Router) extends AnyVal {
        def setName(name: String): Router =
            router.toBuilder.setName(name).build()
        def setTenantId(tenantId: String): Router =
            router.toBuilder.setTenantId(tenantId).build()
        def setAdminStateUp(adminStateUp: Boolean): Router =
            router.toBuilder.setAdminStateUp(adminStateUp).build()
        def setInboundFilterId(filterId: UUID): Router =
            router.toBuilder.setInboundFilterId(filterId.asProto).build()
        def setOutboundFilterId(filterId: UUID): Router =
            router.toBuilder.setOutboundFilterId(filterId.asProto).build()
        def setLoadBalancerId(loadBalancerId: UUID): Router =
            router.toBuilder.setLoadBalancerId(loadBalancerId.asProto).build()
        def setAsNumber(asNumber: Int): Router =
            router.toBuilder.setAsNumber(asNumber).build()
        def addPortId(portId: UUID): Router =
            router.toBuilder.addPortIds(portId.asProto).build()
        def addRouteId(routeId: UUID): Router =
            router.toBuilder.addRouteIds(routeId.asProto).build()
        def clearName(): Router =
            router.toBuilder.clearName().build()
        def clearTenantId(): Router =
            router.toBuilder.clearTenantId().build()
        def clearInboundFilterId(): Router =
            router.toBuilder.clearInboundFilterId().build()
        def clearOutboundFilterId(): Router =
            router.toBuilder.clearOutboundFilterId().build()
        def clearLoadBalancerId(): Router =
            router.toBuilder.clearLoadBalancerId().build()
    }

    final class RichRoute(val route: Route) extends AnyVal {

        def asJava: l3.Route = ZoomConvert.fromProto(route, classOf[l3.Route])

        def setSrcNetwork(ipSubnet: IPSubnet[_]): Route =
            route.toBuilder.setSrcSubnet(ipSubnet.asProto).build()
        def setDstNetwork(ipSubnet: IPSubnet[_]): Route =
            route.toBuilder.setDstSubnet(ipSubnet.asProto).build()
        def setNextHop(nextHop: NextHop): Route =
            route.toBuilder.setNextHop(nextHop).build()
        def setNextHopPortId(portId: UUID): Route =
            route.toBuilder.setNextHopPortId(portId.asProto).build()
        def setNextHopGateway(ipAddress: IPAddr): Route =
            route.toBuilder.setNextHopGateway(ipAddress.asProto).build()
        def setWeight(weight: Int): Route =
            route.toBuilder.setWeight(weight).build()
        def setAttributed(attributes: String): Route =
            route.toBuilder.setAttributes(attributes).build()
        def setRouterId(routerId: UUID): Route =
            route.toBuilder.setRouterId(routerId.asProto).build()
    }

    final class RichChain(val chain: Chain) extends AnyVal {
        def setName(name: String): Chain =
            chain.toBuilder.setName(name).build()
        def addNetworkInboundId(id: UUID): Chain =
            chain.toBuilder.addNetworkInboundIds(id.asProto).build()
        def addNetworkOutboundId(id: UUID): Chain =
            chain.toBuilder.addNetworkOutboundIds(id.asProto).build()
        def addRouterInboundId(id: UUID): Chain =
            chain.toBuilder.addRouterInboundIds(id.asProto).build()
        def addRouterOutboundId(id: UUID): Chain =
            chain.toBuilder.addRouterOutboundIds(id.asProto).build()
        def addPortInboundId(id: UUID): Chain =
            chain.toBuilder.addPortInboundIds(id.asProto).build()
        def addPortOutboundId(id: UUID): Chain =
            chain.toBuilder.addPortOutboundIds(id.asProto).build()
    }

    final class RichPortGroup(val portGroup: PortGroup) extends AnyVal {
        def setName(name: String): PortGroup =
            portGroup.toBuilder.setName(name).build()
        def setTenantId(tenantId: String): PortGroup =
            portGroup.toBuilder.setTenantId(tenantId).build()
        def setStateful(stateful: Boolean): PortGroup =
            portGroup.toBuilder.setStateful(stateful).build()
        def addPortId(portId: UUID): PortGroup =
            portGroup.toBuilder.addPortIds(portId.asProto).build()
    }

    final class RichIpAddrGroup(val ipAddrGroup: IPAddrGroup) extends AnyVal {
        def setName(name: String): IPAddrGroup =
            ipAddrGroup.toBuilder.setName(name).build()
        def setInboundChainId(chainId: UUID): IPAddrGroup =
            ipAddrGroup.toBuilder.setInboundChainId(chainId.asProto).build()
        def setOutboundChainId(chainId: UUID): IPAddrGroup =
            ipAddrGroup.toBuilder.setOutboundChainId(chainId.asProto).build()
        def addRuleId(ruleId: UUID): IPAddrGroup =
            ipAddrGroup.toBuilder.addRuleIds(ruleId.asProto).build()
        def addIpAddrPort(ipAddress: IPAddr, portIds: Set[UUID]): IPAddrGroup =
            ipAddrGroup.toBuilder.addIpAddrPorts(
                IPAddrPorts.newBuilder
                    .setIpAddress(ipAddress.asProto)
                    .addAllPortIds(portIds.map(_.asProto).asJava)
                    .build()).build()
    }

    final class RichPool(val pool: Pool) extends AnyVal {
        def setAdminStateUp(adminStateUp: Boolean): Pool =
            pool.toBuilder.setAdminStateUp(adminStateUp).build()
        def setHealthMonitorId(healthMonitorId: UUID): Pool =
            pool.toBuilder.setHealthMonitorId(healthMonitorId.asProto).build()
        def removeHealthMonitorId(): Pool =
            pool.toBuilder.clearHealthMonitorId().build()
        def setLoadBalancerId(loadBalancerId: UUID): Pool =
            pool.toBuilder.setLoadBalancerId(loadBalancerId.asProto).build()
        def removeLoadBalancerId(): Pool =
            pool.toBuilder.clearLoadBalancerId().build()
        def setProtocol(protocol: PoolProtocol): Pool =
            pool.toBuilder.setProtocol(protocol).build()
        def setLBMethod(lbMethod: PoolLBMethod): Pool =
            pool.toBuilder.setLbMethod(lbMethod).build()
        def addPoolMember(poolMemberId: UUID) =
            pool.toBuilder.addPoolMemberIds(poolMemberId.asProto).build()
        def addVip(vipId: UUID) =
            pool.toBuilder.addVipIds(vipId.asProto).build()
    }

    final class RichPoolMember(val poolMember: PoolMember) extends AnyVal {
        def setAdminStateUp(adminStateUp: Boolean): PoolMember =
            poolMember.toBuilder.setAdminStateUp(adminStateUp).build()
        def setPoolId(poolId: UUID): PoolMember =
            poolMember.toBuilder.setPoolId(poolId.asProto).build()
        def setStatus(status: LBStatus): PoolMember =
            poolMember.toBuilder.setStatus(status).build()
        def setAddress(address: IPAddr): PoolMember =
            poolMember.toBuilder.setAddress(address.asProto).build()
        def setProtocolPort(port: Int): PoolMember =
            poolMember.toBuilder.setProtocolPort(port).build()
        def setWeight(weight: Int): PoolMember =
            poolMember.toBuilder.setWeight(weight).build()
    }

    final class RichLoadBalancer(val loadBalancer: LoadBalancer) extends AnyVal {
        def setAdminStateUp(adminStateUp: Boolean): LoadBalancer =
            loadBalancer.toBuilder.setAdminStateUp(adminStateUp).build()
        def addPool(poolId: UUID): LoadBalancer =
            loadBalancer.toBuilder.addPoolIds(poolId.asProto).build()
    }

    final class RichVip(val vip: Vip) extends AnyVal {
        def setAdminStateUp(adminStateUp: Boolean): Vip =
            vip.toBuilder.setAdminStateUp(adminStateUp).build()
        def setPoolId(poolId: UUID): Vip =
            vip.toBuilder.setPoolId(poolId.asProto).build()
        def setAddress(ipAddress: IPAddr): Vip =
            vip.toBuilder.setAddress(ipAddress.asProto).build()
        def setProtocolPort(port: Int): Vip =
            vip.toBuilder.setProtocolPort(port).build()
        def setSessionPersistence(sessionPersistence: SessionPersistence): Vip =
            vip.toBuilder.setSessionPersistence(sessionPersistence).build()
        def setGatewayPortId(portId: UUID): Vip =
            vip.toBuilder.setGatewayPortId(portId.asProto).build()

        def clearPoolId(): Vip =
            vip.toBuilder.clearPoolId().build()
        def clearAddress(): Vip =
            vip.toBuilder.clearAddress().build()
        def clearProtocolPort(): Vip =
            vip.toBuilder.clearProtocolPort().build()
        def clearSessionPersistence(): Vip =
            vip.toBuilder.clearSessionPersistence().build()
        def clearGatewayPortId(): Vip =
            vip.toBuilder.clearGatewayPortId().build()
    }

    final class RichHealthMonitor(val healthMonitor: HealthMonitor) extends AnyVal {
        def setAdminStateUp(adminStateUp: Boolean): HealthMonitor =
            healthMonitor.toBuilder.setAdminStateUp(adminStateUp).build()
        def setStatus(status: LBStatus): HealthMonitor =
            healthMonitor.toBuilder.setStatus(status).build()
        def setStatus(status: L4LBStatus): HealthMonitor =
            healthMonitor.toBuilder.setStatus(LBStatus.valueOf(status.name()))
                .build()
        def setDelay(delay: Int): HealthMonitor =
            healthMonitor.toBuilder.setDelay(delay).build()
        def setTimeout(timeout: Int): HealthMonitor =
            healthMonitor.toBuilder.setTimeout(timeout).build()
        def setMaxRetries(maxRetries: Int): HealthMonitor =
            healthMonitor.toBuilder.setMaxRetries(maxRetries).build()
        def setPoolId(poolId: UUID): HealthMonitor =
            healthMonitor.toBuilder.setPoolId(poolId.asProto).build()
    }

    final class RichBgpNetwork(val bgpNetwork: BgpNetwork) extends AnyVal {
        def setSubnet(subnet: IPSubnet[_]): BgpNetwork =
            bgpNetwork.toBuilder.setSubnet(subnet.asProto).build()
        def setRouterId(routerId: UUID): BgpNetwork =
            bgpNetwork.toBuilder.setRouterId(routerId.asProto).build()
        def clearSubnet(): BgpNetwork =
            bgpNetwork.toBuilder.clearSubnet().build()
        def clearRouterId(): BgpNetwork =
            bgpNetwork.toBuilder.clearRouterId().build()
    }

    final class RichBgpPeer(val bgpPeer: BgpPeer) extends AnyVal {
        def setAsNumber(asNumber: Int): BgpPeer =
            bgpPeer.toBuilder.setAsNumber(asNumber).build()
        def setAddress(address: IPAddr): BgpPeer =
            bgpPeer.toBuilder.setAddress(address.asProto).build()
        def setRouterId(routerId: UUID): BgpPeer =
            bgpPeer.toBuilder.setRouterId(routerId.asProto).build()
        def clearAsNumber(): BgpPeer =
            bgpPeer.toBuilder.clearAsNumber().build()
        def clearAddress(): BgpPeer =
            bgpPeer.toBuilder.clearAddress().build()
        def clearRouterId(): BgpPeer =
            bgpPeer.toBuilder.clearRouterId().build()
    }

    private val random = new Random()

    def randomIPv4Subnet = new IPv4Subnet(random.nextInt(), random.nextInt(32))

    implicit def asRichPort(port: Port): RichPort = new RichPort(port)

    implicit def asRichBridge(bridge: Network): RichBridge =
        new RichBridge(bridge)

    implicit def asRichRouter(router: Router): RichRouter =
        new RichRouter(router)

    implicit def asRichRoute(route: Route): RichRoute = new RichRoute(route)

    implicit def asRichChain(chain: Chain): RichChain = new RichChain(chain)

    implicit def asRichPortGroup(portGroup: PortGroup): RichPortGroup =
        new RichPortGroup(portGroup)

    implicit def asRichIPAddrGroup(ipAddrGroup: IPAddrGroup): RichIpAddrGroup =
        new RichIpAddrGroup(ipAddrGroup)

    implicit def asRichPool(pool: Pool): RichPool = new RichPool(pool)

    implicit def asRichPoolMember(poolMember: PoolMember): RichPoolMember =
        new RichPoolMember(poolMember)

    implicit def asRichLoadBalancer(loadBalancer: LoadBalancer): RichLoadBalancer =
        new RichLoadBalancer(loadBalancer)

    implicit def asRichVip(vip: Vip): RichVip =
        new RichVip(vip)

    implicit def asRichHealthMonitor(healthMonitor: HealthMonitor): RichHealthMonitor =
        new RichHealthMonitor(healthMonitor)

    implicit def asRichBgpNetwork(bgpNetwork: BgpNetwork): RichBgpNetwork =
        new RichBgpNetwork(bgpNetwork)

    implicit def asRichBgpPeer(bgpPeer: BgpPeer): RichBgpPeer =
        new RichBgpPeer(bgpPeer)

}
