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

import scala.collection.JavaConverters._

import com.fasterxml.jackson.databind.JsonNode
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{HealthMonitor => HealthMonitorType, Pool => PoolType, Router => RouterType}
import org.midonet.cluster.data.neutron.TaskType.Create
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class LoadBalancerIT extends C3POMinionTestBase {
    private def lbPoolJson(id: UUID,
                           adminStateUp: Boolean,
                           routerId: UUID,
                           healthMonitors: List[UUID] = null): JsonNode = {
        val pool = nodeFactory.objectNode
        pool.put("id", id.toString)
        pool.put("admin_state_up", adminStateUp)
        if (routerId != null) pool.put("router_id", routerId.toString)
        if (healthMonitors != null) {
            val monitorsNode = pool.putArray("health_monitors")
            healthMonitors.foreach { hmId => monitorsNode.add(hmId.toString()) }
        }
        pool
    }

    private def healthMonitorJson(id: UUID,
                                  adminStateUp: Boolean,
                                  maxRetries: Int): JsonNode = {
        val hm = nodeFactory.objectNode
        hm.put("id", id.toString)
        hm.put("admin_state_up", adminStateUp)
        hm.put("max_retries", maxRetries)
        hm
    }

    "C3PO" should "be able to set up Load Balancer." in {
        // #1 Create a Router.
        val routerId = UUID.randomUUID()
        val rtrJson = routerJson(routerId, name = "router").toString
        insertCreateTask(1, RouterType, rtrJson, routerId)

        // #2 Create a Load Balancer Pool
        val poolId = UUID.randomUUID()
        val poolJson = lbPoolJson(poolId, true, routerId).toString
        insertCreateTask(2, PoolType, poolJson, poolId)

        val lb = eventually(
                storage.get(classOf[LoadBalancer], routerId).await())
        lb.getId shouldBe toProto(routerId)
        lb.getAdminStateUp shouldBe true
        lb.getRouterId shouldBe toProto(routerId)
        lb.getPoolIdsList should contain (toProto(poolId))

        val router = storage.get(classOf[Router], routerId).await()
        router.getLoadBalancerId shouldBe toProto(routerId)

        val pool = storage.get(classOf[Pool], poolId).await()
        pool.getLoadBalancerId shouldBe toProto(routerId)
        pool.getAdminStateUp shouldBe true
        pool.hasHealthMonitorId shouldBe false

        // #3 Create a Health Monitor.
        val hmId = UUID.randomUUID()
        val hmJson = healthMonitorJson(hmId, true, 10).toString
        insertCreateTask(3, HealthMonitorType, hmJson, hmId)

        val hm = eventually(storage.get(classOf[HealthMonitor], hmId).await())
        hm.getAdminStateUp shouldBe true
        hm.getMaxRetries shouldBe 10

        // #4 Associate the Health Monitor with the Pool
        val poolWithHmJson =
            lbPoolJson(poolId, true, routerId, List(hmId)).toString
        insertUpdateTask(4, PoolType, poolWithHmJson, poolId)
        eventually {
            val pool = storage.get(classOf[Pool], poolId).await()
            pool.hasHealthMonitorId shouldBe true
            pool.getHealthMonitorId shouldBe toProto(hmId)
        }
    }

    "LB Pool" should "be allowed to be created with a Health Monitor already " +
    "associated." in {
        // #1 Create a Router.
        val routerId = UUID.randomUUID()
        val rtrJson = routerJson(routerId, name = "router").toString
        insertCreateTask(1, RouterType, rtrJson, routerId)

        // #2 Create a Health Monitor.
        val hmId = UUID.randomUUID()
        val hmJson = healthMonitorJson(hmId, true, 10).toString
        insertCreateTask(2, HealthMonitorType, hmJson, hmId)

        // #3 Create a Load Balancer Poo with HM already associated.l
        val poolId = UUID.randomUUID()
        val poolWithHmJson = lbPoolJson(poolId, true, routerId,
                                        healthMonitors = List(hmId)).toString
        insertCreateTask(3, PoolType, poolWithHmJson, poolId)
        val pool = eventually(storage.get(classOf[Pool], poolId).await())
        pool.hasHealthMonitorId shouldBe true
        pool.getHealthMonitorId shouldBe toProto(hmId)

        // #4 Remove the Health Monitor from the Pool
        val poolWithNoHmJson = lbPoolJson(poolId, true, routerId).toString
        insertUpdateTask(4, PoolType, poolWithNoHmJson, poolId)
        eventually {
            val pool = storage.get(classOf[Pool], poolId).await()
            pool.hasHealthMonitorId shouldBe false
        }
    }
}