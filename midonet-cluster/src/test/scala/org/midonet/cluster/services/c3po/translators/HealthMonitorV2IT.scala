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
import org.midonet.cluster.data.neutron.NeutronResourceType.{HealthMonitorV2 => HealthMonitorV2Type}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class HealthMonitorV2IT extends C3POMinionTestBase {

    private def makeHmJson(id: UUID,
                           delay: Int,
                           maxRetries: Int,
                           timeout: Int,
                           poolIds: Set[UUID],
                           adminStateUp: Boolean): JsonNode = {
        val hm = nodeFactory.objectNode
        hm.put("id", id.toString)
        hm.put("delay", delay)
        hm.put("timeout", timeout)
        hm.put("max_retries", maxRetries)
        hm.put("admin_state_up", adminStateUp)
        if (poolIds.nonEmpty) {
            val pools = hm.putArray("pools")
            poolIds foreach (p => pools.add(p.toString))
        }
        hm
    }

    private def verifyHealthMonitor(id: UUID, delay: Int,
                                    maxRetries: Int, timeout: Int,
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
        val poolId = UUID.randomUUID
        val poolId2 = UUID.randomUUID
        val pools = Set(poolId, poolId2)
        val hmId = UUID.randomUUID()
        var delay = 1
        var timeout = 1
        var maxRetries = 1

        storage.create(Pool.newBuilder.setId(poolId).build())
        storage.create(Pool.newBuilder.setId(poolId2).build())

        var hmJson = makeHmJson(hmId, delay, timeout, maxRetries, pools, true)

        insertCreateTask(10, HealthMonitorV2Type, hmJson, hmId)

        verifyHealthMonitor(hmId, delay, maxRetries, timeout, pools, true)

        delay = 2
        timeout = 3
        maxRetries = 4
        hmJson = makeHmJson(hmId, delay, maxRetries, timeout, pools, true)

        insertUpdateTask(20, HealthMonitorV2Type, hmJson, hmId)
        verifyHealthMonitor(hmId, delay, maxRetries, timeout, pools, true)

        insertDeleteTask(30, HealthMonitorV2Type, hmId)
        storage.exists(classOf[HealthMonitor], hmId).await() shouldBe false
    }
}
