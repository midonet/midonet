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

import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{LbV2Pool => LbV2PoolType, LbV2PoolMember => LbV2PoolMemberType}
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerV2Pool.LBV2SessionPersistenceType
import org.midonet.cluster.models.Topology.Pool.{PoolLBMethod, PoolProtocol}
import org.midonet.cluster.models.Topology.{Pool, PoolMember, Vip}
import org.midonet.cluster.services.c3po.LbaasV2ITCommon
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class LoadBalancerV2PoolTranslatorIT extends C3POMinionTestBase
                                             with LbaasV2ITCommon {

    "LoadBalancerV2PoolTranslator" should "Create, update, and delete pool" in {
        // Create pool.
        val Boilerplate(_, _, _, lbId) = setUpLb(10)
        val poolId = createLbV2Pool(
            20, lbId,
            sessionPersistenceType = LBV2SessionPersistenceType.SOURCE_IP)
        checkPool(poolId, lbId,
                  sessionPersistence = LBV2SessionPersistenceType.SOURCE_IP)

        // Set admin state down.
        val poolJsonDown = lbV2PoolJson(id = poolId, loadBalancerId = lbId,
                                        adminStateUp = false)
        insertUpdateTask(30, LbV2PoolType, poolJsonDown, poolId)
        checkPool(poolId, lbId, adminStateUp = false)

        // Delete pool.
        insertDeleteTask(40, LbV2PoolType, poolId)
        checkNoPool(poolId)
    }

    it should "Be able to update the session persistence field in the pool" in {
        // Create pool.
        val Boilerplate(_, _, _, lbId) = setUpLb(10)
        val poolId = createLbV2Pool(
            20, lbId,
            sessionPersistenceType = None)
        checkPool(poolId, lbId,
                  sessionPersistence = None)

        val sessionPersistenceJson = lbV2SessionPersistenceJson(
            LBV2SessionPersistenceType.SOURCE_IP)
        val poolJsonSP = lbV2PoolJson(
            id = poolId, loadBalancerId = lbId,
            sessionPersistence = sessionPersistenceJson)

        insertUpdateTask(30, LbV2PoolType, poolJsonSP, poolId)
        checkPool(poolId, lbId,
                  sessionPersistence = LBV2SessionPersistenceType.SOURCE_IP)

        val poolJsonSPClear = lbV2PoolJson(id = poolId, loadBalancerId = lbId)

        insertUpdateTask(30, LbV2PoolType, poolJsonSPClear, poolId)
        checkPool(poolId, lbId,
                  sessionPersistence = None)
    }

    it should "not cascade pool deletion to VIPs" in {
        val Boilerplate(_, _, _, lbId) = setUpLb(10)
        val listenerId = createLbV2Listener(20, lbId)
        val pool1Id = createLbV2Pool(30, lbId, listenerId = listenerId)
        checkPool(pool1Id, lbId, vipIds = Seq(listenerId))

        insertDeleteTask(40, LbV2PoolType, pool1Id)
        checkNoPool(pool1Id)
        storage.exists(classOf[Vip], listenerId).await() shouldBe true
    }

    "LoadBalancerV2PoolMemberTranslator" should
    "Create, update, and delete pool members" in {
        // Create pool and add three members.
        val Boilerplate(_, snId, _, lbId) = setUpLb(10)
        val poolId = createLbV2Pool(20, lbId)
        val m1Id = createLbV2PoolMember(30, poolId, snId, address = "10.0.0.11",
                                        protocolPort = 5001)
        val m2Id = createLbV2PoolMember(40, poolId, snId, address = "10.0.0.12",
                                        weight = 2, protocolPort = 5002)
        val m3Id = createLbV2PoolMember(50, poolId, snId, address = "10.0.0.12",
                                        weight = 3, protocolPort = 5003)

        checkPool(poolId, lbId, memberIds = Seq(m1Id, m2Id, m3Id))
        checkPoolMember(m1Id, poolId, "10.0.0.11", 5001)
        checkPoolMember(m2Id, poolId, "10.0.0.12", 5002, weight = 2)
        checkPoolMember(m3Id, poolId, "10.0.0.12", 5003, weight = 3)

        // Update one member's admin state and weight.
        val updatedM2Json = lbv2PoolMemberJson(
            id = m2Id, address = "10.0.0.12", protocolPort = 5002,
            weight = 10, adminStateUp = false, subnetId = snId, poolId = poolId)
        insertUpdateTask(60, LbV2PoolMemberType, updatedM2Json, m2Id)

        checkPool(poolId, lbId, memberIds = Seq(m1Id, m2Id, m3Id))
        checkPoolMember(m1Id, poolId, "10.0.0.11", 5001)
        checkPoolMember(m2Id, poolId, "10.0.0.12", 5002,
                        weight = 10, adminStateUp = false)
        checkPoolMember(m3Id, poolId, "10.0.0.12", 5003, weight = 3)

        // Delete one member.
        insertDeleteTask(70, LbV2PoolMemberType, m3Id)

        checkPool(poolId, lbId, memberIds = Seq(m1Id, m2Id))
        checkPoolMember(m1Id, poolId, "10.0.0.11", 5001)
        checkPoolMember(m2Id, poolId, "10.0.0.12", 5002,
                        weight = 10, adminStateUp = false)
        checkNoPoolMember(m3Id)

        // Delete the other two.
        insertDeleteTask(80, LbV2PoolMemberType, m1Id)
        insertDeleteTask(90, LbV2PoolMemberType, m2Id)

        checkPool(poolId, lbId, memberIds = Seq())
        checkNoPoolMember(m1Id)
        checkNoPoolMember(m2Id)
        checkNoPoolMember(m3Id)
    }

    case class Boilerplate(nwId: UUID, snId: UUID, vipPortId: UUID, lbId: UUID)
    private def setUpLb(firstTaskId: Int): Boilerplate = {
        val nwId = createTenantNetwork(firstTaskId)
        val snId = createSubnet(firstTaskId + 1, nwId, "10.0.0.0/24")
        val (vipPortId, _, vipSubnetId) = createVipV2PortAndNetwork(
            firstTaskId + 10, "10.0.0.1", "10.0.0.0/24")
        val lbId = createLbV2(firstTaskId + 20, vipPortId, vipSubnetId,
                              "10.0.0.1")
        Boilerplate(nwId, snId, vipPortId, lbId)
    }

    private def checkPool(poolId: UUID, lbId: UUID,
                          adminStateUp: Boolean = true,
                          hmId: Option[UUID] = None,
                          lbMethod: PoolLBMethod = PoolLBMethod.ROUND_ROBIN,
                          protocol: PoolProtocol = PoolProtocol.TCP,
                          memberIds: Seq[UUID] = Seq(),
                          vipIds: Seq[UUID] = Seq(),
                          sessionPersistence: Option[LBV2SessionPersistenceType] = None): Unit = {
        val pool = storage.get(classOf[Pool], poolId).await()
        pool.hasAdminStateUp shouldBe true
        pool.getAdminStateUp shouldBe adminStateUp
        pool.hasLbMethod shouldBe true
        pool.getLbMethod shouldBe lbMethod
        pool.hasLoadBalancerId shouldBe true
        pool.getLoadBalancerId.asJava shouldBe lbId
        pool.getPoolMemberIdsList.asScala.map(_.asJava) should
            contain theSameElementsAs memberIds
        pool.hasProtocol shouldBe true
        pool.getProtocol shouldBe protocol
        pool.getVipIdsList.asScala.map(_.asJava) should
            contain theSameElementsAs vipIds
        if (hmId.isDefined) {
            pool.hasHealthMonitorId shouldBe true
            pool.getHealthMonitorId.asJava shouldBe hmId
        } else {
            pool.hasHealthMonitorId shouldBe false
        }
        if (sessionPersistence.isDefined) {
            pool.hasSessionPersistence shouldBe true
            pool.getSessionPersistence.toString shouldBe sessionPersistence.get.toString
        } else {
            pool.hasSessionPersistence shouldBe false
        }

    }

    private def checkNoPool(poolId: UUID): Unit =
        storage.exists(classOf[Pool], poolId).await() shouldBe false

    private def checkPoolMember(memberId: UUID, poolId: UUID,
                                address: String, protocolPort: Int,
                                adminStateUp: Boolean = true,
                                weight: Int = 1): Unit = {
        val m = storage.get(classOf[PoolMember], memberId).await()
        m.getAddress.getAddress shouldBe address
        m.getAdminStateUp shouldBe adminStateUp
        m.getPoolId.asJava shouldBe poolId
        m.getProtocolPort shouldBe protocolPort
        m.getWeight shouldBe weight
    }

    private def checkNoPoolMember(memberId: UUID): Unit =
        storage.exists(classOf[PoolMember], memberId).await() shouldBe false
}
