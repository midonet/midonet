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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{ListenerV2 => ListenerV2Type}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.LbaasV2ITCommon
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class ListenerV2TranslatorIT extends C3POMinionTestBase
                                     with LbaasV2ITCommon {
    "C3PO" should "be able to create/delete Listener with no pool ID " +
                  "as a VIP with no pool." in {
        val (vipPortId, _, vipSubnetId) = createVipV2PortAndNetwork(1)

        val lbId = createLbV2(10, vipPortId, vipSubnetId, "10.0.1.4")
        val listenerId = createLbV2Listener(10, lbId, defaultPoolId = None,
                           protocolPort = 11100)

        val vip = storage.get(classOf[Vip], listenerId).await()

        vip.getId shouldBe toProto(listenerId)
        vip.getAdminStateUp shouldBe true
        vip.hasPoolId shouldBe false
        vip.getAddress shouldBe IPAddressUtil.toProto("10.0.1.4")
        vip.getProtocolPort shouldBe 11100
        vip.hasSessionPersistence shouldBe false

        insertDeleteTask(30, ListenerV2Type, listenerId)

        storage.exists(classOf[Vip], listenerId).await() shouldBe false
    }

    "C3PO" should "be able to create/delete Listener with default pool ID " +
                  "as a VIP with assigned pool." in {
        val (vipPortId, _, vipSubnetId) = createVipV2PortAndNetwork(1)

        val lbId = createLbV2(10, vipPortId, vipSubnetId, "10.0.1.4")
        val poolId = createLbV2Pool(10, lbId)
        val listenerId = createLbV2Listener(10, lbId, defaultPoolId = poolId,
                                            protocolPort = 11100)

        val vip = storage.get(classOf[Vip], listenerId).await()

        vip.getId shouldBe toProto(listenerId)
        vip.getAdminStateUp shouldBe true
        vip.hasPoolId shouldBe true
        vip.getPoolId shouldBe toProto(poolId)
        vip.getAddress shouldBe IPAddressUtil.toProto("10.0.1.4")
        vip.getProtocolPort shouldBe 11100
        vip.hasSessionPersistence shouldBe false

        insertDeleteTask(30, ListenerV2Type, listenerId)

        storage.exists(classOf[Vip], listenerId).await() shouldBe false
    }

    "C3PO" should "be able to update and clear the pool_id" in {
        val (vipPortId, _, vipSubnetId) = createVipV2PortAndNetwork(1)

        val lbId = createLbV2(10, vipPortId, vipSubnetId, "10.0.1.4")
        val poolId = createLbV2Pool(10, lbId)
        val listenerId = createLbV2Listener(10, lbId, defaultPoolId = poolId,
                                            protocolPort = 11100)

        var vip = storage.get(classOf[Vip], listenerId).await()

        vip.getId shouldBe toProto(listenerId)
        vip.getAdminStateUp shouldBe true
        vip.hasPoolId shouldBe true
        vip.getPoolId shouldBe toProto(poolId)
        vip.getAddress shouldBe IPAddressUtil.toProto("10.0.1.4")
        vip.getProtocolPort shouldBe 11100
        vip.hasSessionPersistence shouldBe false

        updateLbV2Listener(20, lbId, id = listenerId, protocolPort = 11100)

        vip = storage.get(classOf[Vip], listenerId).await()

        vip.getId shouldBe toProto(listenerId)
        vip.getAdminStateUp shouldBe true
        vip.hasPoolId shouldBe false
        vip.getAddress shouldBe IPAddressUtil.toProto("10.0.1.4")
        vip.getProtocolPort shouldBe 11100
        vip.hasSessionPersistence shouldBe false

        updateLbV2Listener(30, lbId, id = listenerId, protocolPort = 11100,
                           adminStateUp = false)

        vip = storage.get(classOf[Vip], listenerId).await()

        vip.getId shouldBe toProto(listenerId)
        vip.getAdminStateUp shouldBe false
        vip.hasPoolId shouldBe false
        vip.getAddress shouldBe IPAddressUtil.toProto("10.0.1.4")
        vip.getProtocolPort shouldBe 11100
        vip.hasSessionPersistence shouldBe false

        updateLbV2Listener(40, lbId, id = listenerId, protocolPort = 11100,
                           defaultPoolId = poolId, adminStateUp = false)

        vip = storage.get(classOf[Vip], listenerId).await()

        vip.getId shouldBe toProto(listenerId)
        vip.getAdminStateUp shouldBe false
        vip.hasPoolId shouldBe true
        vip.getPoolId shouldBe toProto(poolId)
        vip.getAddress shouldBe IPAddressUtil.toProto("10.0.1.4")
        vip.getProtocolPort shouldBe 11100
        vip.hasSessionPersistence shouldBe false
    }
}
