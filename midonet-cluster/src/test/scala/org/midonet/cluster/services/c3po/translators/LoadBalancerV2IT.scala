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
import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{LoadBalancerV2 => LoadBalancerV2Type}
import org.midonet.cluster.data.storage.{NotFoundException, ObjectExistsException}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.LbaasV2ITCommon
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class LoadBalancerV2IT extends C3POMinionTestBase
                               with LoadBalancerManager
                               with LbaasV2ITCommon {
    "C3PO" should "be able to create/delete Load Balancer, Router, and VIP peer port." in {
        val (vipPortId, _, _) = createVipV2PortAndNetwork(1)
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

        insertDeleteTask(50, LoadBalancerV2Type, lbId)
        storage.exists(classOf[LoadBalancer], lbId).await() shouldBe false
        storage.exists(classOf[Router], routerId).await() shouldBe false
        storage.exists(classOf[Port], vipPeerPortId).await() shouldBe false
        storage.exists(classOf[ServiceContainer], lbSCId).await() shouldBe false
        storage.exists(classOf[ServiceContainerGroup], lbSCGId).await() shouldBe false
        storage.exists(classOf[Port], lbSCPId).await() shouldBe false
    }

    "Creation of LB without VIP port" should
      "throw NotFoundException" in {
        val ex = the [TranslationException] thrownBy createLbV2(
            40, UUID.randomUUID(), "10.0.1.4")
        ex.cause shouldBe a [NotFoundException]
    }

    "Creation of LB with already existing router" should
      "throw ObjectExistsException" in {
        val (vipPortId, _, _) = createVipV2PortAndNetwork(1)

        val lbId = UUID.randomUUID
        val routerId = lbV2RouterId(lbId)
        createRouter(10, routerId)

        an [ObjectExistsException] should be thrownBy
        createLbV2(10, vipPortId, "10.0.1.4", id = lbId)
    }

    "Creation of LB with already existing port with same ID as VIP peer port ID" should
      "throw ObjectExistsException" in {
        val (vipPortId, vipNetworkId, vipSubnetId) = createVipV2PortAndNetwork(1)

        val vipPeerPortId = PortManager.routerInterfacePortPeerId(vipPortId)
        createDhcpPort(10, vipNetworkId, vipSubnetId, "10.0.1.5", portId = vipPeerPortId)

        an [ObjectExistsException] should be thrownBy createLbV2(
            20, vipPortId, "10.0.1.4")
    }
}
