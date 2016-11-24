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
import org.midonet.cluster.data.neutron.NeutronResourceType.{
                LoadBalancerV2 => LoadBalancerV2Type,
                Port => PortType,
                Router => RouterType,
                Network => NetworkType,
                Subnet => SubnetType}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class LoadBalancerV2IT extends C3POMinionTestBase with LoadBalancerManager {
    var vipNetworkId: UUID = _
    var vipSubnetId: UUID = _
    var vipPortId: UUID = _

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

    "C3PO" should "be able to create/delete Load Balancer, Router, and VIP peer port." in {
        vipNetworkId = createTenantNetwork(10, external = false)
        vipSubnetId = createSubnet(20, vipNetworkId, "10.0.1.0/24")

        // Create a VIP port
        vipPortId = createVipPort(30, vipNetworkId, vipSubnetId, "10.0.1.4")
        val vipPeerPortId = PortManager.routerInterfacePortPeerId(vipPortId)

        // Create a Load Balancer
        val lbId = UUID.randomUUID
        val routerId = lbV2RouterId(lbId)

        val lbJson = makeLbJson(lbId, vipPortId, "10.0.1.4")

        insertCreateTask(40, LoadBalancerV2Type, lbJson, lbId)

        val lb = eventually(
            storage.get(classOf[LoadBalancer], lbId).await())

        lb.getId shouldBe toProto(lbId)
        lb.getAdminStateUp shouldBe true
        lb.getRouterId shouldBe toProto(routerId)

        val router = storage.get(classOf[Router], routerId).await()
        router.getLoadBalancerId shouldBe toProto(lbId)

        val vipPeerPort = storage.get(classOf[Port], vipPeerPortId).await()
        vipPeerPort.getPeerId shouldBe toProto(vipPortId)

        insertDeleteTask(50, LoadBalancerV2Type, lbId)
        insertDeleteTask(60, PortType, vipPortId)

        eventually {
            storage.exists(classOf[LoadBalancer], lbId).await() shouldBe false
        }
        eventually {
            storage.exists(classOf[Router], routerId).await() shouldBe false
        }
        eventually {
            storage.exists(classOf[Port], vipPeerPortId).await() shouldBe false
        }
        eventually {
            storage.exists(classOf[Port], vipPortId).await() shouldBe false
        }
    }

    "Creation of LB without VIP port" should
      "fail to create LB" in {
        val lbId = UUID.randomUUID
        val routerId = lbV2RouterId(lbId)
        val lbJson = makeLbJson(lbId, UUID.randomUUID(), "10.0.1.4")

        insertCreateTask(10, LoadBalancerV2Type, lbJson, lbId)
        Thread.sleep(2000)
        storage.exists(classOf[LoadBalancer], lbId).await() shouldBe false
    }

    "Creation of LB with already existing router" should
      "fail to create LB" in {
        val lbId = UUID.randomUUID
        val routerId = lbV2RouterId(lbId)
        createRouter(10, routerId)

        val lbJson = makeLbJson(lbId, UUID.randomUUID(), "10.0.1.4")
        insertCreateTask(20, LoadBalancerV2Type, lbJson, lbId)

        Thread.sleep(2000)
        storage.exists(classOf[LoadBalancer], lbId).await() shouldBe false

        insertDeleteTask(30, RouterType, routerId)
    }

    "Creation of LB with already existing port with same ID as VIP peer port ID" should
      "fail to create LB" in {
        val lbId = UUID.randomUUID
        val routerId = lbV2RouterId(lbId)

        // Create a VIP port
        val vipPortId = createVipPort(10, vipNetworkId, vipSubnetId, "10.0.1.4")
        val vipPeerPortId = PortManager.routerInterfacePortPeerId(vipPortId)

        createDhcpPort(20, vipNetworkId, vipSubnetId, "10.0.1.5", vipPeerPortId)

        val lbJson = makeLbJson(lbId, vipPortId, "10.0.1.4")
        insertCreateTask(30, LoadBalancerV2Type, lbJson, lbId)

        Thread.sleep(2000)
        storage.exists(classOf[LoadBalancer], lbId).await() shouldBe false

        insertDeleteTask(40, PortType, vipPortId)
        insertDeleteTask(50, PortType, vipPeerPortId)

        insertDeleteTask(60, SubnetType, vipSubnetId)
        insertDeleteTask(70, NetworkType, vipNetworkId)
    }
}
