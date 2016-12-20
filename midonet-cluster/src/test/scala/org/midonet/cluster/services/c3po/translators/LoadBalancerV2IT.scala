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

import java.util.UUID

import com.fasterxml.jackson.databind.JsonNode
import org.junit.runner.RunWith
import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{LoadBalancerV2 => LoadBalancerV2Type}
import org.midonet.cluster.data.storage.{NotFoundException, ObjectExistsException}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.LbaasV2ITCommon
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps
import org.scalatest.junit.JUnitRunner

import scala.collection.mutable.ListBuffer

@RunWith(classOf[JUnitRunner])
class LoadBalancerV2IT extends C3POMinionTestBase
                       with LoadBalancerManager
                       with ChainManager
                       with LbaasV2ITCommon {

    import PortManager._

    val vipSubAddr = "10.0.1.0"
    val vipSubPrefixLen = 24
    val vipAddr = "10.0.1.4"
    val vipSub = s"$vipSubAddr/$vipSubPrefixLen"
    val containerIp = "169.254.0.2"

    private def makeLbJson(id: UUID,
                           vipPortId: UUID,
                           vipAddress: String,
                           adminStateUp: Boolean = true): JsonNode = {
        val lb = nodeFactory.objectNode
        lb.put("id", id.toString)
        lb.put("admin_state_up", adminStateUp)
        lb.put("vip_port_id", vipPortId.toString)
        lb.put("vip_address", vipAddress)
        lb
    }

    private def createVipPort(ip: String, sub: String): (UUID, UUID, UUID) = {
        val vipNetworkId = createTenantNetwork(10, external = false)
        val vipSubnetId = createSubnet(20, vipNetworkId, sub)
        (createVipPort(30, vipNetworkId, vipSubnetId, ip),
            vipNetworkId,
            vipSubnetId)
    }

    "C3PO" should "be able to create/delete Load Balancer, Router, and VIP peer port." in {
        val (vipPortId, _, vipSubnetId) = createVipV2PortAndNetwork(1)
        val vipPeerPortId = routerInterfacePortPeerId(vipPortId)

        // Create a Load Balancer
        val lbId =  createLbV2(40, vipPortId, vipSubnetId, vipAddr)
        val routerId = lbV2RouterId(lbId)

        val lb = storage.get(classOf[LoadBalancer], lbId).await()

        lb.getId shouldBe toProto(lbId)
        lb.getAdminStateUp shouldBe true
        lb.getRouterId shouldBe toProto(routerId)

        val router = storage.get(classOf[Router], routerId).await()
        router.getLoadBalancerId shouldBe toProto(lbId)

        val preChain = storage.get(classOf[Chain], router.getInboundFilterId).await()
        val revSnatRule = storage.get(classOf[Rule], preChain.getRuleIds(0)).await()
        revSnatRule.getNatRuleData.getDnat shouldBe false
        revSnatRule.getNatRuleData.getReverse shouldBe true

        val postChain = storage.get(classOf[Chain], router.getOutboundFilterId).await()
        val skipNatRule = storage.get(classOf[Rule], postChain.getRuleIds(0)).await()
        skipNatRule.getCondition.getNwDstIp.getAddress shouldBe containerIp

        val snatRule = storage.get(classOf[Rule], postChain.getRuleIds(1)).await()
        snatRule.getNatRuleData.getDnat shouldBe false
        snatRule.getNatRuleData.getNatTargets(0).getNwStart.getAddress shouldBe vipAddr
        snatRule.getNatRuleData.getNatTargets(0).getNwEnd.getAddress shouldBe vipAddr
        snatRule.getCondition.getNwSrcIp.getAddress shouldBe vipAddr
        snatRule.getCondition.getNwSrcInv shouldBe true

        val vipPeerPort = storage.get(classOf[Port], vipPeerPortId).await()
        vipPeerPort.getAdminStateUp shouldBe true
        vipPeerPort.getPeerId shouldBe toProto(vipPortId)
        vipPeerPort.getPortAddress.getAddress shouldBe vipAddr
        vipPeerPort.getPortSubnet(0).getAddress shouldBe vipSubAddr
        vipPeerPort.getPortSubnet(0).getPrefixLength shouldBe vipSubPrefixLen
        vipPeerPort.hasPortMac shouldBe true

        val lbSCId = lbServiceContainerId(routerId)
        val lbSCGId = lbServiceContainerGroupId(routerId)
        val lbSCPId = lbServiceContainerPortId(routerId)

        val sc = storage.get(classOf[ServiceContainer], lbSCId).await()
        sc.getPortId shouldBe toProto(lbSCPId)
        sc.getConfigurationId shouldBe toProto(lb.getId)
        sc.getServiceGroupId shouldBe toProto(lbSCGId)
        sc.getServiceType shouldBe "HAPROXY"

        val scg = storage.get(classOf[ServiceContainerGroup], lbSCGId).await()
        scg.getServiceContainerIdsList should contain only lbSCId

        val scp = storage.get(classOf[Port], lbSCPId).await()
        scp.getRouterId shouldBe routerId
        scp.hasPortMac shouldBe true
        scp.hasPortAddress shouldBe true
        scp.getPortSubnetCount shouldBe 1
        scp.getAdminStateUp shouldBe true

        val routes = storage.getAll(classOf[Route]).await()
        routes.size shouldBe 3
        val expectedNHP = List(vipPeerPortId, vipPeerPortId, scp.getId)
        routes.map(_.getNextHopPortId).toList should contain theSameElementsAs expectedNHP

        insertDeleteTask(50, LoadBalancerV2Type, lbId)
        storage.exists(classOf[LoadBalancer], lbId).await() shouldBe false
        storage.exists(classOf[Router], routerId).await() shouldBe false
        storage.exists(classOf[Chain], router.getInboundFilterId).await() shouldBe false
        storage.exists(classOf[Chain], router.getOutboundFilterId).await() shouldBe false
        storage.exists(classOf[Rule], lbSnatRule(routerId)).await() shouldBe false
        storage.exists(classOf[Rule], lbRevSnatRule(routerId)).await() shouldBe false
        storage.exists(classOf[Port], vipPeerPortId).await() shouldBe false
        storage.exists(classOf[ServiceContainer], lbSCId).await() shouldBe false
        storage.exists(classOf[ServiceContainerGroup], lbSCGId).await() shouldBe false
        storage.exists(classOf[Port], lbSCPId).await() shouldBe false

        routes.map(_.getId).foreach(storage.exists(classOf[Route], _).await() shouldBe false)
    }

    "Creation of LB without VIP port" should
      "throw NotFoundException" in {
        val ex = the [TranslationException] thrownBy createLbV2(
            40, UUID.randomUUID(), UUID.randomUUID(), "10.0.1.4")
        ex.cause shouldBe a [NotFoundException]
    }

    "Creation of LB with already existing router" should
      "throw ObjectExistsException" in {
        val (vipPortId, _, vipSubnetId) = createVipV2PortAndNetwork(1)

        val lbId = UUID.randomUUID
        val routerId = lbV2RouterId(lbId)
        createRouter(10, routerId)

        an [ObjectExistsException] should be thrownBy
        createLbV2(10, vipPortId, vipSubnetId, "10.0.1.4", id = lbId)
    }

    "Creation of LB with already existing port with same ID as VIP peer port ID" should
    "throw ObjectExistsException" in {
        val (vipPortId, vipNetworkId, vipSubnetId) = createVipV2PortAndNetwork(1)

        val vipPeerPortId = routerInterfacePortPeerId(vipPortId)
        createDhcpPort(10, vipNetworkId, vipSubnetId, "10.0.1.5", portId = vipPeerPortId)

        an [ObjectExistsException] should be thrownBy createLbV2(
            20, vipPortId, vipSubnetId, "10.0.1.4")
    }


    def verifyGatewayRoute(portId: UUID, gatewayIp: String,
                           hostRoutes: List[HostRoute] = List()): Unit = {
        val routerPortId = routerInterfacePortPeerId(portId)
        val rPort = storage.get(classOf[Port], routerPortId).await()
        val routes = storage.getAll(classOf[Route], rPort.getRouteIdsList).await()

        val actualRoutes = routes.filter(_.hasNextHopGateway).map(midoRouteToHostRoute)
        val expectedHostRoutes = ListBuffer(HostRoute("0.0.0.0/0", gatewayIp)) ++ hostRoutes
        actualRoutes should contain theSameElementsAs expectedHostRoutes

    }

    "Updating the LB subnet's gateway IP" should "change the routes on the LB routers" in {
        val vip1Ip = "10.0.1.4"
        val vip2Ip = "10.0.1.5"

        val vipSub = "10.0.1.0/24"

        val gatewayIp1 = "10.0.1.10"
        val gatewayIp2 = "10.0.1.11"

        val (vipPortId, vipNetworkId, vipSubnetId) = createVipV2PortAndNetwork(
            1, ipAddr = vip1Ip, gatewayIp = gatewayIp1, netAddr = vipSub)

        val vipPortId2 = createVipV2Port(10, vipNetworkId, vipSubnetId, vip2Ip)

        createLbV2(20, vipPortId, vipSubnetId, vip1Ip)
        createLbV2(30, vipPortId2, vipSubnetId, vip2Ip)

        verifyGatewayRoute(vipPortId, gatewayIp1)
        verifyGatewayRoute(vipPortId2, gatewayIp1)

        val hostRoutes = List(HostRoute("10.10.10.0/24", "1.1.1.1"),
                              HostRoute("10.20.20.0/24", "2.2.2.2"))

        updateSubnet(40, vipNetworkId, vipSub, vipSubnetId, gatewayIp2,
                     hostRoutes = hostRoutes)

        verifyGatewayRoute(vipPortId, gatewayIp2, hostRoutes)
        verifyGatewayRoute(vipPortId2, gatewayIp2, hostRoutes)
    }
}
