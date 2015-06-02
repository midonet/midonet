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
import org.midonet.cluster.data.neutron.NeutronResourceType.{HealthMonitor => HealthMonitorType, Pool => PoolType, PoolMember => PoolMemberType, Router => RouterType, VIP => VIPType}
import org.midonet.cluster.data.neutron.TaskType.Create
import org.midonet.cluster.models.Commons.LBStatus.ACTIVE
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.models.Topology.HealthMonitor.HealthMonitorType.TCP
import org.midonet.cluster.util.IPAddressUtil
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

    private case class HmPool(poolId: UUID, status: String, statusDesc: String)
    private def healthMonitorJson(id: UUID,
                                  adminStateUp: Boolean,
                                  delay: Int,
                                  maxRetries: Int,
                                  timeout: Int,
                                  pools: Seq[HmPool] = null,
                                  hmType: String = null): JsonNode = {
        val hm = nodeFactory.objectNode
        hm.put("id", id.toString)
        hm.put("admin_state_up", adminStateUp)
        hm.put("delay", delay)
        hm.put("max_retries", maxRetries)
        hm.put("timeout", timeout)
        if (pools != null)
            pools.foreach { pool =>
                val poolNode = nodeFactory.objectNode
                poolNode.put("pool_id", pool.poolId.toString)
                if (pool.status != null)
                    poolNode.put("status", pool.status)
                if (pool.statusDesc != null)
                    poolNode.put("status_description", pool.statusDesc)
                hm.set("pools", poolNode)
            }
        hm.put("type", hmType)
        hm
    }

    private def memberJson(id: UUID,
                           poolId: UUID,
                           address: String,
                           adminStateUp: Boolean = true,
                           protocolPort: Int = 12345,
                           weight: Int = 100,
                           status: String = "status",
                           statusDescription: String = "status_desc")
                           : JsonNode = {
        val member = nodeFactory.objectNode
        member.put("id", id.toString)
        if (poolId != null) member.put("pool_id", poolId.toString)
        if (address != null) member.put("address", address)
        member.put("admin_state_up", adminStateUp)
        member.put("protocol_port", protocolPort)
        member.put("weight", weight)
        if (status != null) member.put("status", status)
        if (statusDescription != null)
            member.put("status_description", statusDescription)
        member
    }

    private def vipJson(id: UUID,
                        adminStateUp: Boolean = true,
                        connectionLimit: Int = 10,
                        poolId: UUID = null,
                        portId: UUID = null,
                        protocolPort: Int = 12345,
                        sessionPersistenceType: String = "SOURCE_IP",
                        cookieName: String = "cookie0",
                        statusDescription: String = null,
                        subnetId: UUID = null): JsonNode = {
        val vip = nodeFactory.objectNode
        vip.put("id", id.toString)
        vip.put("admin_state_up", adminStateUp)
        vip.put("collection_limit", connectionLimit)
        if (poolId != null) vip.put("pool_id", poolId.toString)
        if (portId != null) vip.put("port_id", portId.toString)
        if (protocolPort > 0) vip.put("protocol_port", protocolPort)
        if (sessionPersistenceType != null && cookieName != null) {
            val spNode = nodeFactory.objectNode
            if (sessionPersistenceType != null)
                spNode.put("session_perss", sessionPersistenceType)
            if (cookieName != null) spNode.put("cookie_name", cookieName)
            vip.set("session_persistence", spNode)
        }
        if (statusDescription != null)
            vip.put("status_description", statusDescription)
        if (subnetId != null) vip.put("subnet_id", subnetId.toString)
        vip
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
        pool.getPoolMemberIdsList.isEmpty shouldBe true

        // #3 Create a Health Monitor.
        val hmId = UUID.randomUUID()
        val hmJson = healthMonitorJson(id = hmId, adminStateUp = true,
                                       maxRetries = 10, delay = 2,
                                       timeout = 30, hmType = "foo").toString
        insertCreateTask(3, HealthMonitorType, hmJson, hmId)
        /*
id: UUID,
                                  adminStateUp: Boolean,
                                  delay: Int,
                                  maxRetries: Int,
                                  timeout: Int,
                                  pools: Seq[HmPool] = null,
                                  hmType: String = null
         */

        val hm = eventually(storage.get(classOf[HealthMonitor], hmId).await())
        hm.getAdminStateUp shouldBe true
        hm.getMaxRetries shouldBe 10
        hm.getDelay shouldBe 2
        hm.getTimeout shouldBe 30
        hm.getType shouldBe TCP
        hm.getStatus shouldBe ACTIVE

        // #4 Associate the Health Monitor with the Pool
        val poolWithHmJson =
            lbPoolJson(poolId, true, routerId, List(hmId)).toString
        insertUpdateTask(4, PoolType, poolWithHmJson, poolId)
        eventually {
            val pool = storage.get(classOf[Pool], poolId).await()
            pool.hasHealthMonitorId shouldBe true
            pool.getHealthMonitorId shouldBe toProto(hmId)
        }

        // #5 Update the Health Monitor's max retries, etc.
        // "pools" contain the Pool associated above.
        val updatedHmJson = healthMonitorJson(id = hmId,
                adminStateUp = false, maxRetries = 5, delay = 3, timeout = 20,
                pools = List(HmPool(poolId, "status", "desc"))).toString
        insertUpdateTask(5, HealthMonitorType, updatedHmJson, hmId)

        eventually {
            val updatedHm = storage.get(classOf[HealthMonitor], hmId).await()
            updatedHm.getAdminStateUp shouldBe false
            updatedHm.getMaxRetries shouldBe 5
            updatedHm.getDelay shouldBe 3
            updatedHm.getTimeout shouldBe 20
            updatedHm.getType shouldBe TCP
            updatedHm.getStatus shouldBe ACTIVE
        }

        // #6 Create a VIP.
        val vipId = UUID.randomUUID()
        val vJson = vipJson(vipId, poolId = poolId).toString
        insertCreateTask(6, VIPType, vJson, vipId)
        val vip = eventually(storage.get(classOf[VIP], vipId).await())
        vip.getLoadBalancerId shouldBe lb.getId
        vip.getAdminStateUp shouldBe true
        vip.getPoolId shouldBe toProto(poolId)
        vip.getProtocolPort shouldBe 12345
        vip.hasSessionPersistence shouldBe true
        vip.getSessionPersistence shouldBe VIP.SessionPersistence.SOURCE_IP
        val lbWithVip = storage.get(classOf[LoadBalancer], routerId).await()
        lbWithVip.getVipIdsList should contain (toProto(vipId))

        // #7 Add a Pool Member.
        val memberId = UUID.randomUUID()
        val poolMemberAddress = "10.10.0.3"
        val poolMemberJson =
            memberJson(memberId, poolId, poolMemberAddress).toString
        insertCreateTask(7, PoolMemberType, poolMemberJson, memberId)
        val member =
            eventually(storage.get(classOf[PoolMember], memberId).await())
        member.getPoolId shouldBe toProto(poolId)
        member.getAddress shouldBe IPAddressUtil.toProto(poolMemberAddress)
        val poolWithMember = storage.get(classOf[Pool], poolId).await()
        poolWithMember.getPoolMemberIdsList should contain (toProto(memberId))

        // #8 Delete the Pool Member
        insertDeleteTask(8, PoolMemberType, memberId)
        eventually {
            storage.exists(classOf[PoolMember], memberId).await() shouldBe false
        }
        val poolWithNoMember = storage.get(classOf[Pool], poolId).await()
        poolWithNoMember.getPoolMemberIdsList.isEmpty shouldBe true

        // #9 Add a new Pool Member 2 to the Pool.
        val member2Id = UUID.randomUUID()
        val poolMember2Address = "10.10.0.4"
        val poolMember2Json =
            memberJson(member2Id, poolId, poolMember2Address).toString
        insertCreateTask(9, PoolMemberType, poolMember2Json, member2Id)
        val member2 =
            eventually(storage.get(classOf[PoolMember], member2Id).await())
        member2.getPoolId shouldBe toProto(poolId)
        member2.getAddress shouldBe IPAddressUtil.toProto(poolMember2Address)
        eventually {
            // Pool Member update above should update the Pool's references to
            // its members in a same multi op, but since ZOOM looks in the
            // Observables cache in get(), the update to Pool may not have been
            // reflected right after the member was updated.
            val poolWMember2 = storage.get(classOf[Pool], poolId).await()
            poolWMember2.getPoolMemberIdsList should contain (toProto(member2Id))
        }

        // #10 Delete the Pool
        insertDeleteTask(10, PoolType, poolId)
        eventually {
            storage.exists(classOf[Pool], poolId).await() shouldBe false
            val parentlessMem2 = storage.get(classOf[PoolMember], member2Id)
                                        .await()
            parentlessMem2.hasPoolId shouldBe false
            val lbWithNoPool = storage.get(classOf[LoadBalancer], routerId)
                                      .await()
            lbWithNoPool.getPoolIdsList shouldBe empty
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
        val hmJson = healthMonitorJson(id = hmId, adminStateUp = true,
                                       maxRetries = 10, delay = 2,
                                       timeout = 30).toString
        insertCreateTask(2, HealthMonitorType, hmJson, hmId)

        // #3 Create a Load Balancer Pool with HM already associated.
        val poolId = UUID.randomUUID()
        val poolWithHmJson = lbPoolJson(poolId, true, routerId,
                                        healthMonitors = List(hmId)).toString
        insertCreateTask(3, PoolType, poolWithHmJson, poolId)
        val pool = eventually(storage.get(classOf[Pool], poolId).await())
        pool.hasHealthMonitorId shouldBe true
        pool.getHealthMonitorId shouldBe toProto(hmId)
        pool.getPoolMemberIdsList shouldBe empty

        // #4 Add a Pool Member with no Pool ID specified.
        val memberId = UUID.randomUUID()
        val poolMemberAddress = "10.10.0.3"
        val poolMemberJson =
            memberJson(memberId, poolId = null, poolMemberAddress).toString
        insertCreateTask(4, PoolMemberType, poolMemberJson, memberId)
        val member =
            eventually(storage.get(classOf[PoolMember], memberId).await())
        member.hasPoolId shouldBe false
        member.getAddress shouldBe IPAddressUtil.toProto(poolMemberAddress)
        member.getStatus shouldBe ACTIVE
        val samePool = storage.get(classOf[Pool], poolId).await()
        samePool.getPoolMemberIdsList shouldBe empty

        // #5 Update a Pool Member with Pool ID and other properties.
        val memberAddress2 = "10.10.0.4"
        val updatedMemberJson =
            memberJson(memberId, poolId, memberAddress2,
                       adminStateUp = false, protocolPort = 23456, weight = 200,
                       status = "status2").toString
        insertUpdateTask(5, PoolMemberType, updatedMemberJson, memberId)
        eventually {
            val updatedMember =
                storage.get(classOf[PoolMember], memberId).await()
            updatedMember.hasPoolId shouldBe true
            updatedMember.getPoolId shouldBe toProto(poolId)
            updatedMember.getAddress shouldBe IPAddressUtil.toProto(
                    memberAddress2)
            updatedMember.getAdminStateUp shouldBe false
            updatedMember.getProtocolPort shouldBe 23456
            updatedMember.getWeight shouldBe 200

            val poolWithMember = storage.get(classOf[Pool], poolId).await()
            poolWithMember.getPoolMemberIdsList should contain (
                    toProto(memberId))
        }

        // #6 Create a 2nd Pool.
        val pool2Id = UUID.randomUUID()
        val pool2Json = lbPoolJson(pool2Id, true, routerId).toString
        insertCreateTask(6, PoolType, pool2Json, pool2Id)
        val pool2 = eventually(storage.get(classOf[Pool], pool2Id).await())
        pool2.getPoolMemberIdsList.isEmpty shouldBe true

        // #7 Re-attach the Pool Member to Pool2.
        val movedMemberJson =
            memberJson(memberId, pool2Id, memberAddress2).toString
        insertUpdateTask(7, PoolMemberType, movedMemberJson, memberId)
        eventually {
            val movedMember =
                storage.get(classOf[PoolMember], memberId).await()
            movedMember.hasPoolId shouldBe true
            movedMember.getPoolId shouldBe toProto(pool2Id)
            val poolWithNoMember = storage.get(classOf[Pool], poolId).await()
            poolWithNoMember.getPoolMemberIdsList shouldBe empty
            val pool2WithMember = storage.get(classOf[Pool], pool2Id).await()
            pool2WithMember.getPoolMemberIdsList should contain (
                    toProto(memberId))
        }

        // #8 Detach the Pool Member from Pool2.
        val detachedMemberJson =
            memberJson(memberId, poolId = null, memberAddress2).toString
        insertUpdateTask(8, PoolMemberType, detachedMemberJson, memberId)
        eventually {
            val detachedMember =
                storage.get(classOf[PoolMember], memberId).await()
            detachedMember.hasPoolId shouldBe false
            val pool2WithNoMember = storage.get(classOf[Pool], pool2Id).await()
            pool2WithNoMember.getPoolMemberIdsList shouldBe empty
        }

        // #9 Remove the Health Monitor from the Pool
        val poolWithNoHmJson = lbPoolJson(poolId, true, routerId).toString
        insertUpdateTask(9, PoolType, poolWithNoHmJson, poolId)
        eventually {
            val pool = storage.get(classOf[Pool], poolId).await()
            pool.hasHealthMonitorId shouldBe false
        }
    }
}