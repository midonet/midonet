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
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{LoadBalancerV2 => LoadBalancerV2Type}
import org.midonet.cluster.data.storage.{NotFoundException, ObjectExistsException}
import org.midonet.cluster.models.Topology.{ServiceContainer => SC, ServiceContainerGroup => SCG, _}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class LoadBalancerV2IT extends C3POMinionTestBase
                       with LoadBalancerManager
                       with ChainManager {

    val vipAddr = "10.0.1.4"
    val vipSub = "10.0.1.0/24"

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
        val (vipPortId, _, _) = createVipPort(vipAddr, vipSub)
        val vipPeerPortId = PortManager.routerInterfacePortPeerId(vipPortId)

        // Create a Load Balancer
        val lbId = UUID.randomUUID
        val routerId = lbV2RouterId(lbId)

        val lbJson = makeLbJson(lbId, vipPortId, vipAddr)

        insertCreateTask(40, LoadBalancerV2Type, lbJson, lbId)
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
        val snatRule = storage.get(classOf[Rule], postChain.getRuleIds(0)).await()
        snatRule.getNatRuleData.getDnat shouldBe false
        snatRule.getNatRuleData.getNatTargets(0).getNwStart.getAddress shouldBe vipAddr
        snatRule.getNatRuleData.getNatTargets(0).getNwEnd.getAddress shouldBe vipAddr
        snatRule.getCondition.getNwSrcIp.getAddress shouldBe vipAddr
        snatRule.getCondition.getNwSrcInv shouldBe true

        val vipPeerPort = storage.get(classOf[Port], vipPeerPortId).await()
        vipPeerPort.getPeerId shouldBe toProto(vipPortId)

        val lbSCId = lbServiceContainerId(routerId)
        val lbSCGId = lbServiceContainerGroupId(routerId)
        val lbSCPId = lbServiceContainerPortId(routerId)

        storage.exists(classOf[SC], lbSCId).await() shouldBe true
        val sc = storage.get(classOf[SC], lbSCId).await()
        sc.getPortId shouldBe toProto(lbSCPId)
        sc.getConfigurationId shouldBe toProto(lb.getId)
        sc.getServiceGroupId shouldBe toProto(lbSCGId)

        storage.exists(classOf[SCG], lbSCGId).await() shouldBe true
        val scg = storage.get(classOf[SCG], lbSCGId).await()
        scg.getServiceContainerIdsCount shouldBe 1
        scg.getServiceContainerIdsList.get(0) shouldBe lbSCId

        storage.exists(classOf[Port], lbSCPId).await() shouldBe true
        val scp = storage.get(classOf[Port], lbSCPId).await()
        scp.getRouterId shouldBe routerId

        insertDeleteTask(50, LoadBalancerV2Type, lbId)
        storage.exists(classOf[LoadBalancer], lbId).await() shouldBe false
        storage.exists(classOf[Router], routerId).await() shouldBe false
        storage.exists(classOf[Chain], router.getInboundFilterId).await() shouldBe false
        storage.exists(classOf[Chain], router.getOutboundFilterId).await() shouldBe false
        storage.exists(classOf[Rule], lbSnatRule(routerId)).await() shouldBe false
        storage.exists(classOf[Rule], lbDnatRule(routerId)).await() shouldBe false
        storage.exists(classOf[Port], vipPeerPortId).await() shouldBe false
        storage.exists(classOf[SC], lbSCId).await() shouldBe false
        storage.exists(classOf[SCG], lbSCGId).await() shouldBe false
        storage.exists(classOf[Port], lbSCPId).await() shouldBe false
    }

    "Creation of LB without VIP port" should
      "throw NotFoundException" in {
        val lbId = UUID.randomUUID
        val lbJson = makeLbJson(lbId, UUID.randomUUID(), "10.0.1.4")

        val ex = the [TranslationException] thrownBy insertCreateTask(
            10, LoadBalancerV2Type, lbJson, lbId)
        ex.cause shouldBe a [NotFoundException]
    }

    "Creation of LB with already existing router" should
      "throw ObjectExistsException" in {
        val (vipPortId, _, _) = createVipPort(vipAddr, vipSub)

        val lbId = UUID.randomUUID
        val routerId = lbV2RouterId(lbId)
        createRouter(10, routerId)

        val lbJson = makeLbJson(lbId, vipPortId, "10.0.1.4")
        an [ObjectExistsException] should be thrownBy insertCreateTask(
            20, LoadBalancerV2Type, lbJson, lbId)
    }

    "Creation of LB with already existing port with same ID as VIP peer port ID" should
      "throw ObjectExistsException" in {
        val (vipPortId, vipNetworkId, vipSubnetId) = createVipPort(vipAddr, vipSub)

        val lbId = UUID.randomUUID

        val vipPeerPortId = PortManager.routerInterfacePortPeerId(vipPortId)

        createDhcpPort(20, vipNetworkId, vipSubnetId, "10.0.1.5", portId = vipPeerPortId)

        val lbJson = makeLbJson(lbId, vipPortId, "10.0.1.4")
        an [ObjectExistsException] should be thrownBy insertCreateTask(
            30, LoadBalancerV2Type, lbJson, lbId)
    }
}
