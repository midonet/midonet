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
import org.midonet.cluster.data.neutron.NeutronResourceType.{HealthMonitor => HealthMonitorType, Pool => PoolType, PoolMember => PoolMemberType, Router => RouterType, VIP => VIPType}
import org.midonet.cluster.models.Commons.LBStatus.ACTIVE
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.models.Topology.HealthMonitor.HealthMonitorType.TCP
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.packets.MAC
import org.midonet.packets.util.AddressConversions._
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class LoadBalancerIT extends C3POMinionTestBase {
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true

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
            healthMonitors.foreach { hmId => monitorsNode.add(hmId.toString) }
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
                        address: String = null,
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
        if (address != null) vip.put("address", address)
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
        val networkId = UUID.randomUUID()
        val subnetId = UUID.randomUUID()
        createTenantNetwork(2, networkId, external = false)
        createSubnet(3, subnetId, networkId, "10.0.1.0/24")

        // Create a Router.
        val routerId = UUID.randomUUID()
        val rtrJson = routerJson(routerId, name = "router").toString
        insertCreateTask(4, RouterType, rtrJson, routerId)

        // Create a Load Balancer Pool
        val poolId = UUID.randomUUID()
        val poolJson = lbPoolJson(poolId, true, routerId).toString
        insertCreateTask(5, PoolType, poolJson, poolId)

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

        // Create a Health Monitor.
        val hmId = UUID.randomUUID()
        val hmJson = healthMonitorJson(id = hmId, adminStateUp = true,
                                       maxRetries = 10, delay = 2,
                                       timeout = 30, hmType = "foo").toString
        insertCreateTask(6, HealthMonitorType, hmJson, hmId)

        val hm = eventually(storage.get(classOf[HealthMonitor], hmId).await())
        hm.getAdminStateUp shouldBe true
        hm.getMaxRetries shouldBe 10
        hm.getDelay shouldBe 2
        hm.getTimeout shouldBe 30
        hm.getType shouldBe TCP
        hm.getStatus shouldBe ACTIVE
        hm.hasPoolId shouldBe false

        // Associate the Health Monitor with the Pool
        val poolWithHmJson =
            lbPoolJson(poolId, true, routerId, List(hmId)).toString
        insertUpdateTask(7, PoolType, poolWithHmJson, poolId)
        eventually {
            val poolFtr = storage.get(classOf[Pool], poolId)
            val associatedHmFtr = storage.get(classOf[HealthMonitor], hmId)
            val pool = poolFtr.await()
            val associatedHm = associatedHmFtr.await()
            pool.hasHealthMonitorId shouldBe true
            pool.getHealthMonitorId shouldBe toProto(hmId)
            associatedHm.getPoolId shouldBe toProto(poolId)
        }

        // Update the Health Monitor's max retries, etc.
        // "pools" field would contain the Pool associated above, validated and
        // populated by Neutron. The association between Health Monitor and Pool
        // will never be updated from the Health Monitor side, and always be
        // done from the Pool side. Neutron will make sure that the correct
        // value are passed down and the translator does not check.
        val updatedHmJson = healthMonitorJson(id = hmId,
                adminStateUp = false, maxRetries = 5, delay = 3, timeout = 20,
                pools = List(HmPool(poolId, "status", "desc"))).toString
        insertUpdateTask(8, HealthMonitorType, updatedHmJson, hmId)

        eventually {
            val updatedHm = storage.get(classOf[HealthMonitor], hmId).await()
            updatedHm.getAdminStateUp shouldBe false
            updatedHm.getMaxRetries shouldBe 5
            updatedHm.getDelay shouldBe 3
            updatedHm.getTimeout shouldBe 20
            updatedHm.getType shouldBe TCP
            updatedHm.getStatus shouldBe ACTIVE
            updatedHm.getPoolId shouldBe toProto(poolId)
        }

        // Create a VIP.
        val vipId = UUID.randomUUID()
        val vJson = vipJson(vipId, address = "10.0.0.2", poolId = poolId,
                            subnetId = subnetId)
        insertCreateTask(9, VIPType, vJson.toString, vipId)
        val vip = eventually(storage.get(classOf[Vip], vipId).await())
        vip.getLoadBalancerId shouldBe lb.getId
        vip.getAdminStateUp shouldBe true
        vip.getPoolId shouldBe toProto(poolId)
        vip.getAddress shouldBe IPAddressUtil.toProto("10.0.0.2")
        vip.getProtocolPort shouldBe 12345
        vip.hasSessionPersistence shouldBe true
        vip.getSessionPersistence shouldBe Vip.SessionPersistence.SOURCE_IP
        val lbWithVip = storage.get(classOf[LoadBalancer], routerId).await()
        lbWithVip.getVipIdsList should contain (toProto(vipId))

        // Add a Pool Member.
        val memberId = UUID.randomUUID()
        val poolMemberAddress = "10.10.0.3"
        val poolMemberJson =
            memberJson(memberId, poolId, poolMemberAddress).toString
        insertCreateTask(10, PoolMemberType, poolMemberJson, memberId)
        val member =
            eventually(storage.get(classOf[PoolMember], memberId).await())
        member.getPoolId shouldBe toProto(poolId)
        member.getAddress shouldBe IPAddressUtil.toProto(poolMemberAddress)
        val poolWithMember = storage.get(classOf[Pool], poolId).await()
        poolWithMember.getPoolMemberIdsList should contain (toProto(memberId))

        // Delete the Pool Member
        insertDeleteTask(11, PoolMemberType, memberId)
        eventually {
            storage.exists(classOf[PoolMember], memberId).await() shouldBe false
        }
        val poolWithNoMember = storage.get(classOf[Pool], poolId).await()
        poolWithNoMember.getPoolMemberIdsList.isEmpty shouldBe true

        // Add a new Pool Member 2 to the Pool.
        val member2Id = UUID.randomUUID()
        val poolMember2Address = "10.10.0.4"
        val poolMember2Json =
            memberJson(member2Id, poolId, poolMember2Address).toString
        insertCreateTask(12, PoolMemberType, poolMember2Json, member2Id)
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

        // Delete the Health Monitor
        insertDeleteTask(13, HealthMonitorType, hmId)
        eventually {
            storage.exists(classOf[HealthMonitor], hmId).await() shouldBe false
            val poolWithoutHm = storage.get(classOf[Pool], poolId).await()
            poolWithoutHm.hasHealthMonitorId shouldBe false
        }

        // Delete the Pool
        insertDeleteTask(14, PoolType, poolId)
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
        val networkId = UUID.randomUUID()
        val subnetId = UUID.randomUUID()
        createTenantNetwork(2, networkId, external = false)
        createSubnet(3, subnetId, networkId, "10.10.0.0/24")

        // Create a Router.
        val routerId = UUID.randomUUID()
        val rtrJson = routerJson(routerId, name = "router").toString
        insertCreateTask(4, RouterType, rtrJson, routerId)

        // Create a Health Monitor.
        val hmId = UUID.randomUUID()
        val hmJson = healthMonitorJson(id = hmId, adminStateUp = true,
                                       maxRetries = 10, delay = 2,
                                       timeout = 30).toString
        insertCreateTask(5, HealthMonitorType, hmJson, hmId)

        // Create a Load Balancer Pool with HM already associated.
        val poolId = UUID.randomUUID()
        val poolWithHmJson = lbPoolJson(poolId, true, routerId,
                                        healthMonitors = List(hmId)).toString
        insertCreateTask(6, PoolType, poolWithHmJson, poolId)
        val pool = eventually(storage.get(classOf[Pool], poolId).await())
        pool.hasHealthMonitorId shouldBe true
        pool.getHealthMonitorId shouldBe toProto(hmId)
        pool.getPoolMemberIdsList shouldBe empty
        // A Load Balancer object should be created.
        val lb = eventually(
                storage.get(classOf[LoadBalancer], routerId).await())

        // Add a Pool Member with no Pool ID specified.
        val memberId = UUID.randomUUID()
        val poolMemberAddress = "10.10.0.3"
        val poolMemberJson =
            memberJson(memberId, poolId = null, poolMemberAddress).toString
        insertCreateTask(7, PoolMemberType, poolMemberJson, memberId)
        val member =
            eventually(storage.get(classOf[PoolMember], memberId).await())
        member.hasPoolId shouldBe false
        member.getAddress shouldBe IPAddressUtil.toProto(poolMemberAddress)
        member.getStatus shouldBe ACTIVE
        val samePool = storage.get(classOf[Pool], poolId).await()
        samePool.getPoolMemberIdsList shouldBe empty

        // Update a Pool Member with Pool ID and other properties.
        val memberAddress2 = "10.10.0.4"
        val updatedMemberJson =
            memberJson(memberId, poolId, memberAddress2,
                       adminStateUp = false, protocolPort = 23456, weight = 200,
                       status = "status2").toString
        insertUpdateTask(8, PoolMemberType, updatedMemberJson, memberId)
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

        // Create a 2nd Router and Pool.
        val router2Id = UUID.randomUUID()
        val rtr2Json = routerJson(router2Id, name = "router2").toString
        insertCreateTask(9, RouterType, rtr2Json, router2Id)
        val pool2Id = UUID.randomUUID()
        val pool2Json = lbPoolJson(pool2Id, true, router2Id).toString
        insertCreateTask(10, PoolType, pool2Json, pool2Id)
        val pool2 = eventually(storage.get(classOf[Pool], pool2Id).await())
        pool2.getPoolMemberIdsList.isEmpty shouldBe true

        // Re-attach the Pool Member to Pool2.
        val movedMemberJson =
            memberJson(memberId, pool2Id, memberAddress2).toString
        insertUpdateTask(11, PoolMemberType, movedMemberJson, memberId)
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

        // Detach the Pool Member from Pool2.
        val detachedMemberJson =
            memberJson(memberId, poolId = null, memberAddress2).toString
        insertUpdateTask(12, PoolMemberType, detachedMemberJson, memberId)
        eventually {
            val detachedMember =
                storage.get(classOf[PoolMember], memberId).await()
            detachedMember.hasPoolId shouldBe false
            val pool2WithNoMember = storage.get(classOf[Pool], pool2Id).await()
            pool2WithNoMember.getPoolMemberIdsList shouldBe empty
        }

        // Remove the Health Monitor from the Pool
        val poolWithNoHmJson = lbPoolJson(poolId, true, routerId).toString
        insertUpdateTask(13, PoolType, poolWithNoHmJson, poolId)
        eventually {
            val pool = storage.get(classOf[Pool], poolId).await()
            pool.hasHealthMonitorId shouldBe false
            val detachedHm = storage.get(classOf[HealthMonitor], hmId).await()
            detachedHm.hasPoolId shouldBe false
        }

        // Create a VIP.
        val vipId = UUID.randomUUID()
        val vJson = vipJson(vipId, address = "10.10.0.2", poolId = poolId,
                            subnetId = subnetId)
        insertCreateTask(14, VIPType, vJson.toString, vipId)
        val vip = eventually(storage.get(classOf[Vip], vipId).await())
        vip.getLoadBalancerId shouldBe lb.getId
        vip.getAdminStateUp shouldBe true
        vip.getPoolId shouldBe toProto(poolId)
        vip.getAddress shouldBe IPAddressUtil.toProto("10.10.0.2")
        vip.getProtocolPort shouldBe 12345
        vip.hasSessionPersistence shouldBe true
        vip.getSessionPersistence shouldBe Vip.SessionPersistence.SOURCE_IP
        val lbWithVip = eventually(storage.get(classOf[LoadBalancer], routerId)
                                   .await())
        lbWithVip.getVipIdsList should contain (toProto(vipId))

        // Update the VIP.
        val updatedVipJson = vipJson(vipId, poolId = pool2Id,
                                     adminStateUp = false,
                                     address = "10.10.0.4",
                                     subnetId = subnetId,
                                     protocolPort = 54321).toString
        insertUpdateTask(15, VIPType, updatedVipJson, vipId)
        eventually {
            val updatedVip = storage.get(classOf[Vip], vipId).await()
            updatedVip.getLoadBalancerId shouldBe toProto(router2Id)
            updatedVip.getAdminStateUp shouldBe false
            updatedVip.getPoolId shouldBe toProto(pool2Id)
            updatedVip.getAddress shouldBe IPAddressUtil.toProto("10.10.0.4")
            updatedVip.getProtocolPort shouldBe 54321
            updatedVip.hasSessionPersistence shouldBe true
            updatedVip.getSessionPersistence shouldBe
                    Vip.SessionPersistence.SOURCE_IP
            val lbWithVipRemoved = storage.get(classOf[LoadBalancer], routerId)
                                          .await()
            lbWithVipRemoved.getVipIdsList shouldBe empty
            val lb2 = storage.get(classOf[LoadBalancer], router2Id).await()
            lb2.getVipIdsList should contain (toProto(vipId))
        }

        // Delete the VIP
        insertDeleteTask(16, VIPType, vipId)
        eventually {
            storage.exists(classOf[Vip], vipId).await() shouldBe false
            val lb2NoVip = storage.get(classOf[LoadBalancer], router2Id)
                                  .await()
            lb2NoVip.getVipIdsList shouldBe empty
        }
    }

    "VIPTranslator" should "add an ARP table entry if when the VIP is on an " +
    "external Network" in {
        val extNwId = UUID.randomUUID()
        val tntRtr1Id = UUID.randomUUID()
        val rtrGwPortId = UUID.randomUUID()
        val subnetId = UUID.randomUUID()
        val poolId = UUID.randomUUID()
        val vipId = UUID.randomUUID()
        val vipPortId = UUID.randomUUID()
        val rtrGwPortMac = "ab:cd:ef:01:02:03"

        // Create a tenant router with a gateway via external network.
        createTenantNetwork(2, extNwId, external = true)
        createSubnet(3, subnetId, extNwId, "10.0.1.0/24")
        createRouterGatewayPort(4, rtrGwPortId, extNwId, tntRtr1Id,
                                "10.0.1.1", rtrGwPortMac, subnetId)
        createRouter(5, tntRtr1Id, rtrGwPortId)

        // Create a Load Balancer Pool on the tenant Router.
        val poolJson = lbPoolJson(poolId, true, tntRtr1Id).toString
        insertCreateTask(6, PoolType, poolJson, poolId)

        val lb = eventually(
                storage.get(classOf[LoadBalancer], tntRtr1Id).await())
        lb.getId shouldBe toProto(tntRtr1Id)
        lb.getAdminStateUp shouldBe true
        lb.getRouterId shouldBe toProto(tntRtr1Id)
        lb.getPoolIdsList should contain (toProto(poolId))

        val rtrGwPort = storage.get(classOf[Port], rtrGwPortId).await()
        rtrGwPort.hasNetworkId shouldBe true
        rtrGwPort.getNetworkId shouldBe toProto(extNwId)
        rtrGwPort.hasPeerId shouldBe true

        val rtrPort = storage.get(classOf[Port], rtrGwPort.getPeerId).await()
        rtrPort.hasRouterId shouldBe true
        rtrPort.getRouterId shouldBe toProto(tntRtr1Id)
        rtrPort.hasPortMac shouldBe true
        rtrPort.getPortMac shouldBe rtrGwPortMac

        // Create a VIP on the external Network. The VIP Port referenced by
        // vipId may not exist on the MidoNet side.
        val vipAddress = "10.0.1.10"
        val vJson = vipJson(vipId, address = vipAddress, portId = vipPortId,
                            poolId = poolId, subnetId = subnetId)
        insertCreateTask(7, VIPType, vJson.toString, vipId)
        val vip = eventually(storage.get(classOf[Vip], vipId).await())
        vip.getPoolId shouldBe toProto(poolId)
        vip.getLoadBalancerId shouldBe toProto(tntRtr1Id)

        // Create a legacy ReplicatedMap for the exterenal Network ARP table.
        val arpTable = dataClient.getIp4MacMap(extNwId)
        arpTable shouldNot be(null)
        arpTable.start()
        eventually {
            // The ARP table should pick up the pre-seeded MAC.
            arpTable.get(vipAddress) shouldBe MAC.fromString(rtrGwPortMac)
        }

        // Delete the VIP.
        insertDeleteTask(8, VIPType, vipId)
        eventually {
            storage.exists(classOf[Vip], vipId).await() shouldBe false
            arpTable.containsKey(vipAddress) shouldBe false
        }
        arpTable.stop()
    }
}
