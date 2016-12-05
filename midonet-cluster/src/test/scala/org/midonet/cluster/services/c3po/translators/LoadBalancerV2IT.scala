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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{LoadBalancerV2 => LoadBalancerV2Type}
import org.midonet.cluster.data.storage.{NotFoundException, ObjectExistsException}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class LoadBalancerV2IT extends C3POMinionTestBase with LoadBalancerManager {
    "C3PO" should "be able to create/delete Load Balancer, Router, and VIP peer port." in {
        val (vipPortId, _, _) = createVipV2Port()
        val vipPeerPortId = PortManager.routerInterfacePortPeerId(vipPortId)

        // Create a Load Balancer
        val lbId =  createLbV2(40, vipPortId, "10.0.1.4")
        val routerId = lbV2RouterId(lbId)

        val lb = storage.get(classOf[LoadBalancer], lbId).await()

        lb.getId shouldBe toProto(lbId)
        lb.getAdminStateUp shouldBe true
        lb.getRouterId shouldBe toProto(routerId)

        val router = storage.get(classOf[Router], routerId).await()
        router.getLoadBalancerId shouldBe toProto(lbId)

        val vipPeerPort = storage.get(classOf[Port], vipPeerPortId).await()
        vipPeerPort.getPeerId shouldBe toProto(vipPortId)

        insertDeleteTask(50, LoadBalancerV2Type, lbId)
        storage.exists(classOf[LoadBalancer], lbId).await() shouldBe false
        storage.exists(classOf[Router], routerId).await() shouldBe false
        storage.exists(classOf[Port], vipPeerPortId).await() shouldBe false
    }

    "Creation of LB without VIP port" should
      "throw NotFoundException" in {
        val ex = the [TranslationException] thrownBy createLbV2(
            40, UUID.randomUUID(), "10.0.1.4")
        ex.cause shouldBe a [NotFoundException]
    }

    "Creation of LB with already existing router" should
      "throw ObjectExistsException" in {
        val (vipPortId, _, _) = createVipV2Port()

        val lbId = UUID.randomUUID
        val routerId = lbV2RouterId(lbId)
        createRouter(10, routerId)

        an [ObjectExistsException] should be thrownBy
        createLbV2(10, vipPortId, "10.0.1.4", id = lbId)
    }

    "Creation of LB with already existing port with same ID as VIP peer port ID" should
      "throw ObjectExistsException" in {
        val (vipPortId, vipNetworkId, vipSubnetId) = createVipV2Port()

        val vipPeerPortId = PortManager.routerInterfacePortPeerId(vipPortId)
        createDhcpPort(20, vipNetworkId, vipSubnetId, "10.0.1.5", portId = vipPeerPortId)

        an [ObjectExistsException] should be thrownBy createLbV2(
            10, vipPortId, "10.0.1.4")
    }
}
