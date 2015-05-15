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
import org.midonet.cluster.data.neutron.NeutronResourceType.{Pool => PoolType, Router => RouterType}
import org.midonet.cluster.data.neutron.TaskType.Create
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class LoadBalancerPoolTranslatorIT extends C3POMinionTestBase {
    private def lbPoolJson(id: UUID,
                           adminStateUp: Boolean,
                           routerId: UUID): JsonNode = {
        val pool = nodeFactory.objectNode
        pool.put("id", id.toString)
        pool.put("admin_state_up", adminStateUp)
        if (routerId != null) pool.put("router_id", routerId.toString)
        pool
    }

    "C3PO" should "create a LoadBalancer for the router when a first Load " +
    "Balancer Pool is created." in {
        // (#1 Flush done in before())
        // #2 Create a Router.
        val routerId = UUID.randomUUID()
        val rtrJson = routerJson(routerId, name = "router").toString
        insertCreateTask(2, RouterType, rtrJson, routerId)

        // #3 Create a Load Balancer Pool
        val poolId = UUID.randomUUID()
        val poolJson = lbPoolJson(poolId, true, routerId).toString
        insertCreateTask(3, PoolType, poolJson, routerId)

        val lb = eventually(
                storage.get(classOf[LoadBalancer], routerId).await())
        lb.getId shouldBe toProto(routerId)
        lb.getAdminStateUp shouldBe true
        lb.getRouterId shouldBe toProto(routerId)
        val router = storage.get(classOf[Router], routerId).await()
        router.getLoadBalancerId shouldBe toProto(routerId)
    }
}