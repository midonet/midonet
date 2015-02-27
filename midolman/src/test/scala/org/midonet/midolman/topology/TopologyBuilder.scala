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
import org.midonet.cluster.models.Topology.Host.PortToInterface
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology.Rule.{NatRuleData, Action, JumpRuleData, NatTarget}
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.{IPSubnetUtil, RangeUtil, IPAddressUtil}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.rules.{FragmentPolicy, Condition}
import org.midonet.packets._
import org.midonet.util.Range

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
                                  vtepMgmtIp: IPAddr = IPv4Addr.random,
                                  vtepMgmtPort: Int = random.nextInt(),
                                  vtepVni: Int = random.nextInt(),
                                  vtepTunnelIp: IPAddr = IPv4Addr.random,
                                  vtepTunnelZoneId: UUID = UUID.randomUUID)
    : Port = {
        val builder = createPortBuilder(
            id, inboundFilterId, outboundFilterId, tunnelKey, peerId, vifId,
            hostId, interfaceName, adminStateUp, portGroupIds)
            .setVtepMgmtIp(vtepMgmtIp.asProto)
            .setVtepMgmtPort(vtepMgmtPort)
            .setVtepVni(vtepVni)
            .setVtepTunnelIp(vtepTunnelIp.asProto)
            .setVtepTunnelZoneId(vtepTunnelZoneId.asProto)
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
            .addAllPortInterfaceMapping(
                portBindings.map(e => PortToInterface.newBuilder
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
                               gwPortId: Option[UUID] = None,
                               routes: Seq[Route] = Seq.empty,
                               portIds: Set[UUID] = Set.empty): Router = {
        val builder = Router.newBuilder
            .setId(id.asProto)
            .setAdminStateUp(adminStateUp)
            .addAllRoutes(routes.asJava)
            .addAllPortIds(portIds.map(_.asProto).asJava)
        if (tenandId.isDefined) builder.setTenantId(tenandId.get)
        if (name.isDefined) builder.setName(name.get)
        if (inboundFilterId.isDefined)
            builder.setInboundFilterId(inboundFilterId.get.asProto)
        if (outboundFilterId.isDefined)
            builder.setOutboundFilterId(outboundFilterId.get.asProto)
        if (loadBalancerId.isDefined)
            builder.setLoadBalancerId(loadBalancerId.get.asProto)
        if (gwPortId.isDefined)
            builder.setGwPortId(gwPortId.get.asProto)
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


    protected def setConditionAllFieldsDefault(builder: Rule.Builder,
        conjunctionInv: Boolean = true,
        matchForwardFlow: Option[Boolean] = None,
        inPortIds: Set[UUID] = Set(UUID.randomUUID()),
        inPortInv: Boolean = true,
        outPortIds: Set[UUID] = Set(UUID.randomUUID()),
        outPortInv: Boolean = true,
        portGroup: UUID = UUID.randomUUID(),
        invPortGroup: Boolean = true,
        ipAddrGroupIdSrc: UUID = UUID.randomUUID(),
        invIpAddrGroupIdSrc: Boolean = true,
        ipAddrGroupIdDst: UUID = UUID.randomUUID(),
        invIpAddrGroupIdDst: Boolean = true,
        etherType: Int = random.nextInt,
        invDlType: Boolean = true,
        ethSrc: MAC = MAC.random,
        ethSrcMask: Long = Condition.NO_MASK,
        invDlSrc: Boolean = true,
        ethDst: MAC = MAC.random,
        dlDstMask: Long = Condition.NO_MASK,
        invDlDst: Boolean = true,
        nwTos: Byte =
            random.nextInt(128).asInstanceOf[Byte],
        nwTosInv: Boolean = true,
        nwProto: Byte =
            random.nextInt(128).asInstanceOf[Byte],
        nwProtoInv: Boolean = true,
        nwSrcIp: IPSubnet[_] = randomIPv4Subnet,
        nwDstIp: IPSubnet[_] = randomIPv4Subnet,
        tpSrc: Range[Integer] = randomRange,
        tpDst: Range[Integer] = randomRange,
        nwSrcInv: Boolean = true,
        nwDstInv: Boolean = true,
        tpSrcInv: Boolean = true,
        tpDstInv: Boolean = true,
        traversedDevice: UUID = UUID.randomUUID,
        traversedDeviceInv: Boolean = true,
        fragmentPolicy: FragmentPolicy =
            FragmentPolicy.ANY): Rule.Builder = {

        if (matchForwardFlow.isDefined) {
            builder.setMatchForwardFlow(matchForwardFlow.get)
            builder.setMatchReturnFlow(!matchForwardFlow.get)
        }
        builder
            .setConjunctionInv(conjunctionInv)
            .addAllInPortIds(inPortIds.map(_.asProto).asJava)
            .setInPortInv(inPortInv)
            .addAllOutPortIds(outPortIds.map(_.asProto).asJava)
            .setOutPortInv(outPortInv)
            .setPortGroupId(portGroup.asProto)
            .setInvPortGroup(invPortGroup)
            .setIpAddrGroupIdSrc(ipAddrGroupIdSrc.asProto)
            .setInvIpAddrGroupIdSrc(invIpAddrGroupIdSrc)
            .setIpAddrGroupIdDst(ipAddrGroupIdDst.asProto)
            .setInvIpAddrGroupIdDst(invIpAddrGroupIdDst)
            .setDlType(etherType)
            .setInvDlType(invDlType)
            .setDlSrc(ethSrc.toString)
            .setDlSrcMask(ethSrcMask)
            .setInvDlSrc(invDlSrc)
            .setDlDst(ethDst.toString)
            .setDlDstMask(dlDstMask)
            .setInvDlDst(invDlDst)
            .setNwTos(nwTos)
            .setNwTosInv(nwTosInv)
            .setNwProto(nwProto)
            .setNwProtoInv(nwProtoInv)
            .setNwSrcIp(IPSubnetUtil.toProto(nwSrcIp))
            .setNwDstIp(IPSubnetUtil.toProto(nwDstIp))
            .setTpSrc(RangeUtil.toProto(tpSrc))
            .setTpDst(RangeUtil.toProto(tpDst))
            .setNwSrcInv(nwSrcInv)
            .setNwDstInv(nwDstInv)
            .setTpSrcInv(tpSrcInv)
            .setTpDstInv(tpDstInv)
            .setTraversedDevice(traversedDevice.asProto)
            .setTraversedDeviceInv(traversedDeviceInv)
            .setFragmentPolicy(Rule.FragmentPolicy.valueOf(fragmentPolicy.name))
    }

    protected def setCondition(builder: Rule.Builder,
                               conjunctionInv: Option[Boolean] = None,
                               matchForwardFlow: Option[Boolean] = None,
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

        if (matchForwardFlow.isDefined) {
            builder.setMatchForwardFlow(matchForwardFlow.get)
            builder.setMatchReturnFlow(!matchForwardFlow.get)
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
                Rule.FragmentPolicy.valueOf(fragmentPolicy.get.name))

        builder
    }

    private def createRuleBuilder(id: UUID, chainId: Option[UUID],
                                  action: Option[Rule.Action],
                                  matchFwdFlow: Option[Boolean] = None)
    : Rule.Builder = {
        val builder = Rule.newBuilder.setId(id.asProto)
        if (chainId.isDefined)
            builder.setChainId(chainId.get.asProto)
        if (action.isDefined)
            builder.setAction(action.get)

        if (matchFwdFlow.isDefined)
            setConditionAllFieldsDefault(builder, matchForwardFlow = matchFwdFlow)
        else
            setConditionAllFieldsDefault(builder)
        builder
    }

    protected def createLiteralRule(id: UUID,
                                    chainId: Option[UUID] = None,
                                    action: Option[Rule.Action] = None)
    : Rule = {
        createRuleBuilder(id, chainId, action)
            .setType(Rule.Type.LITERAL_RULE)
            .build()
    }

    protected def createTraceRule(id: UUID,
                                  chainId: Option[UUID] = None): Rule = {
        createRuleBuilder(id, chainId, Option(Action.CONTINUE))
            .setType(Rule.Type.TRACE_RULE)
            .build()
    }

    protected def createJumpRule(id: UUID, chainId: Option[UUID] = None,
                                 jumpChainId: Option[UUID] = None): Rule = {
        val builder = createRuleBuilder(id, chainId, Option(Action.JUMP))
            .setType(Rule.Type.JUMP_RULE)

        if (jumpChainId.isDefined)
            builder.setJumpRuleData(JumpRuleData.newBuilder
                                        .setJumpTo(jumpChainId.get.asProto)
                                        .build())
        builder.build()
    }

    protected def createNatTarget(startAddr: IPAddress =
                                      IPAddressUtil.toProto(IPv4Addr.random),
                                  endAddr: IPAddress =
                                      IPAddressUtil.toProto(IPv4Addr.random),
                                  portStart: Int = random.nextInt,
                                  portEnd: Int = random.nextInt): NatTarget = {
        NatTarget.newBuilder
            .setNwStart(startAddr)
            .setNwEnd(endAddr)
            .setTpStart(portStart)
            .setTpEnd(portEnd)
            .build()
    }

    protected def createNatRule(id: UUID, chainId: Option[UUID] = None,
                                matchFwdFlow: Option[Boolean] = None,
                                dnat: Option[Boolean] = None,
                                targets:  Set[NatTarget]): Rule = {
        val builder = createRuleBuilder(id, chainId, Option(Action.CONTINUE),
                                        matchFwdFlow)
            .setType(Rule.Type.NAT_RULE)
            .setNatRuleData(NatRuleData.newBuilder
                .addAllNatTargets(targets.asJava)
                .build())

        if (dnat.isDefined)
            builder.getNatRuleDataBuilder
                .setDnat(dnat.get)
                .build()

        builder.build()
    }

    protected def createChain(id: UUID, name: Option[String],
                              ruleIds: Seq[Commons.UUID]): Chain = {
        val builder = Chain.newBuilder
            .setId(id.asProto)
            .addAllRuleIds(ruleIds.asJava)

        if (name.isDefined)
            builder.setName(name.get)

        builder.build()
    }

    protected def createIPSubnetBuilder(version: IPVersion, prefix: String,
                                        prefixLength: Int): Commons.IPSubnet.Builder = {
        Commons.IPSubnet.newBuilder
            .setVersion(version)
            .setAddress(prefix)
            .setPrefixLength(prefixLength)
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

    private val random = new Random()

    def randomIPv4Subnet = new IPv4Subnet(random.nextInt, random.nextInt(32))

    def randomRange = {
        val start = random.nextInt(65534)
        val end = start + random.nextInt(65535 - start)
        new Range[Integer](start, end)
    }
}
