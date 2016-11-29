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

package org.midonet.cluster.services.c3po.translators

import java.util.UUID

import com.fasterxml.jackson.databind.JsonNode

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{ListenerV2 => ListenerV2Type, LoadBalancerV2 => LoadBalancerV2Type}
import org.midonet.cluster.data.storage.{NotFoundException, ObjectExistsException}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class ListenerV2TranslatorIT extends C3POMinionTestBase {

    private def createVipPort(): (UUID, UUID, UUID) = {
        val vipNetworkId = createTenantNetwork(10, external = false)
        val vipSubnetId = createSubnet(20, vipNetworkId, "10.0.1.0/24")
        (createVipPort(30, vipNetworkId, vipSubnetId, "10.0.1.4"),
            vipNetworkId,
            vipSubnetId)
    }

    "C3PO" should "be able to create/delete Listener with no pool ID " +
                  "as a VIP with no pool." in {
        val (vipPortId, _, _) = createVipPort()

        val lbId = UUID.randomUUID
        val listenerId = UUID.randomUUID

        val lbJson = lbV2Json(lbId, vipPortId, "10.0.1.4")
        val listenerJson = lbv2ListenerJson(listenerId, lbId, None,
                                            protocolPort = 10000)

        insertCreateTask(10, LoadBalancerV2Type, lbJson, lbId)

        insertCreateTask(20, ListenerV2Type, listenerJson, lbId)

        val vip = storage.get(classOf[Vip], listenerId).await()

        vip.getId shouldBe toProto(listenerId)
        vip.getAdminStateUp shouldBe true
        vip.hasPoolId shouldBe false
        vip.getAddress shouldBe IPAddressUtil.toProto("10.0.1.4")
        vip.getProtocolPort shouldBe 10000
        vip.hasSessionPersistence shouldBe false

        insertDeleteTask(30, ListenerV2Type, listenerId)

        storage.exists(classOf[Vip], listenerId).await() shouldBe false
    }

}
