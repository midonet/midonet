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
import org.midonet.cluster.data.neutron.NeutronResourceType.{HealthMonitorV2 => HealthMonitorV2Type}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.LbaasV2ITCommon
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class HealthMonitorV2IT extends C3POMinionTestBase with LbaasV2ITCommon {

    private def verifyHealthMonitor(id: UUID, delay: Int,
                                    timeout: Int, maxRetries: Int,
                                    poolIds: Set[UUID],
                                    adminStateUp: Boolean): Unit = {
        val hm = storage.get(classOf[HealthMonitor], id).await()
        hm.getAdminStateUp shouldBe adminStateUp
        hm.getDelay shouldBe delay
        hm.getTimeout shouldBe timeout
        hm.getMaxRetries shouldBe maxRetries
        hm.getId shouldBe toProto(id)
        hm.getPoolIdsList should contain theSameElementsAs (poolIds map toProto)
    }

    "HealthMonitorV2Translator" should "add, update, and delete HM" in {
        val (vipPortId, _, _) = createVipV2PortAndNetwork(1)

        val lbId = createLbV2(10, vipPortId, "10.0.1.4")

        val pool1Id = createLbV2Pool(20, lbId)
        val pool2Id = createLbV2Pool(30, lbId)
        val pools = Set(pool1Id, pool2Id)

        val hmId = createHealthMonitorV2(40, poolIds = pools)

        verifyHealthMonitor(hmId, 1, 1, 1, pools, true)

        val pool1 = storage.get(classOf[Pool], pool1Id).await()
        pool1.getHealthMonitorId shouldBe toProto(hmId)

        val pool2 = storage.get(classOf[Pool], pool1Id).await()
        pool2.getHealthMonitorId shouldBe toProto(hmId)

        updateHealthMonitorV2(50, hmId, 2, 3, 4, true)
        verifyHealthMonitor(hmId, 2, 3, 4, pools, true)

        insertDeleteTask(60, HealthMonitorV2Type, hmId)
        storage.exists(classOf[HealthMonitor], hmId).await() shouldBe false
    }
}
