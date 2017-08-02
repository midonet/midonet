/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.c3po.translators

import org.midonet.cluster.conf.ClusterConfig
import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{NeutronLoadBalancerV2, NeutronPort}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.containers
import org.midonet.packets.{IPv4Subnet, MAC}
import scala.collection.mutable.ListBuffer

class LoadBalancerV2Translator(config: ClusterConfig)
        extends Translator[NeutronLoadBalancerV2]
        with LoadBalancerManager
        with ChainManager
        with RuleManager {
    /**
      *  Neutron does not maintain the back reference to the Floating IP, so we
      * need to do that by ourselves.
      */
    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[NeutronLoadBalancerV2])
    : List[Operation[NeutronLoadBalancerV2]] = List()

    override protected def translateCreate(tx: Transaction,
                                           nLb: NeutronLoadBalancerV2)
    : Unit = {
        if (!nLb.hasVipPortId || !nLb.hasVipAddress)
            throw new IllegalArgumentException(
                "VIP port must be created and specified along with the VIP address.")

        val subnet = containers.findLocalSubnet()
        val routerAddress = containers.routerPortAddress(subnet)
        val containerAddress = containers.containerPortAddress(subnet)
        val routerSubnet = new IPv4Subnet(routerAddress, subnet.getPrefixLen)

        val newRouterId = lbV2RouterId(nLb.getId)
        val newRouterPortId = PortManager.routerInterfacePortPeerId(nLb.getVipPortId)

        val iChainId = inChainId(newRouterId)
        val oChainId = outChainId(newRouterId)

        val vipAddr = IPSubnetUtil.fromAddress(nLb.getVipAddress)
        val containerAddr = IPSubnetUtil.fromAddress(containerAddress.toString)

        val snatRule = Rule.newBuilder
            .setId(lbSnatRule(newRouterId))
            .setType(Rule.Type.NAT_RULE)
            .setAction(Rule.Action.ACCEPT)
            .setCondition(
                anyFragCondition
                    // The source IP is NOT the vip addr AND the destination
                    // IP is NOT the container Addr
                    .setNwDstIp(containerAddr)
                    .setNwDstInv(true)
                    .setNwSrcIp(vipAddr)
                    .setNwSrcInv(true))
            .setNatRuleData(
                natRuleData(IPAddressUtil.toProto(nLb.getVipAddress),
                            dnat = false,
                            dynamic = true,
                            config.translators.dynamicNatPortStart,
                            config.translators.dynamicNatPortEnd))
            .build()

        val revSnatRule = Rule.newBuilder
            .setId(lbRevSnatRule(newRouterId))
            .setType(Rule.Type.NAT_RULE)
            .setAction(Rule.Action.ACCEPT)
            .setCondition(anyFragCondition)
            .setNatRuleData(reverseNatRuleData(dnat = false))
            .build()

        // Create the router for this LB
        val newRouter = Router.newBuilder()
                              .setId(newRouterId)
                              .setInboundFilterId(iChainId)
                              .setOutboundFilterId(oChainId)
                              .setAdminStateUp(nLb.getAdminStateUp)
                              .build

        val inChain = newChain(iChainId, lbRouterInChainName(nLb.getId),
                               Seq(revSnatRule.getId))
        val outChain = newChain(oChainId, lbRouterOutChainName(nLb.getId),
                                Seq(snatRule.getId))

        // Create load balancer object with this new router
        val lb = LoadBalancer.newBuilder()
            .setId(nLb.getId)
            .setAdminStateUp(nLb.getAdminStateUp)
            .setRouterId(newRouterId)
            .setServiceContainerId(lbServiceContainerId(newRouterId))
            .build

        val dhcp = tx.get(classOf[Dhcp], nLb.getVipSubnetId)
        val nVipPort = tx.get(classOf[NeutronPort], nLb.getVipPortId)

        // Create a router-side port with the VIP address, and connect to the
        // already-created VIP port (specified by vipPortId)
        val newPort = Port.newBuilder()
            .setId(newRouterPortId)
            .setPeerId(nLb.getVipPortId)
            .setRouterId(newRouterId)
            .setPortAddress(IPAddressUtil.toProto(nLb.getVipAddress))
            .setPortMac(nVipPort.getMacAddress)
            .addPortSubnet(dhcp.getSubnetAddress)
            .build

        val routes = ListBuffer(newNextHopPortRoute(
            newPort.getId, dstSubnet = newPort.getPortSubnet(0)))

        val serviceContainerPort = Port.newBuilder
            .setId(lbServiceContainerPortId(newRouter.getId))
            .setRouterId(newRouter.getId)
            .addPortSubnet(routerSubnet.asProto)
            .setPortAddress(routerAddress.asProto)
            .setPortMac(MAC.random().toString)
            .build()

        routes += newNextHopPortRoute(
            serviceContainerPort.getId,
            dstSubnet = serviceContainerPort.getPortSubnet(0))

        if (dhcp.hasDefaultGateway) {
            routes += defaultGwRoute(dhcp, newPort.getId,
                                     dhcp.getDefaultGateway)
        }
        routes ++= buildRouterRoutesFromDhcp(newPort.getId, dhcp)

        val serviceContainerGroup = ServiceContainerGroup.newBuilder
            .setId(lbServiceContainerGroupId(newRouterId))
            .build()

        val serviceContainer = ServiceContainer.newBuilder
            .setId(lbServiceContainerId(newRouterId))
            .setServiceGroupId(serviceContainerGroup.getId)
            .setPortId(serviceContainerPort.getId)
            .setServiceType("HAPROXY")
            .setConfigurationId(lb.getId)
            .build()

        tx.create(snatRule)
        tx.create(revSnatRule)
        tx.create(inChain)
        tx.create(outChain)
        tx.create(newRouter)
        tx.create(lb)
        tx.create(newPort)
        tx.create(serviceContainerPort)
        tx.create(serviceContainerGroup)
        tx.create(serviceContainer)
        routes foreach tx.create
    }

    override protected def translateDelete(tx: Transaction, id: UUID): Unit = {
        val routerId = lbV2RouterId(id)
        val sCGId = lbServiceContainerGroupId(routerId)
        tx.delete(classOf[Chain], inChainId(routerId), ignoresNeo = true)
        tx.delete(classOf[Chain], outChainId(routerId), ignoresNeo = true)
        tx.delete(classOf[LoadBalancer], id, ignoresNeo = true)
        tx.delete(classOf[Router], routerId, ignoresNeo = true)
        tx.delete(classOf[ServiceContainerGroup], sCGId, ignoresNeo = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           nLb: NeutronLoadBalancerV2)
    : Unit = {
        // Only adminStateUp is updateable for LoadBalancerV2
        val oldLb = tx.get(classOf[LoadBalancer], nLb.getId)
        val updatedLb = oldLb.toBuilder
            .setAdminStateUp(nLb.getAdminStateUp)
            .build()
        tx.update(updatedLb)
    }

}
