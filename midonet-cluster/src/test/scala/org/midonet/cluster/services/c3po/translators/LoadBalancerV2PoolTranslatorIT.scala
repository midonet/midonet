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
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerV2Pool.LBV2SessionPersistenceType
import org.midonet.cluster.models.Topology.{Pool, PoolMember}
import org.midonet.cluster.models.Topology.Pool.{PoolLBMethod, PoolProtocol}
import org.midonet.util.concurrent.toFutureOps
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import scala.collection.JavaConverters._

import org.midonet.cluster.data.neutron.NeutronResourceType.{LbV2Pool => LbV2PoolType, LbV2PoolMember => LbV2PoolMemberType}

@RunWith(classOf[JUnitRunner])
class LoadBalancerV2PoolTranslatorIT extends C3POMinionTestBase {

    "LoadBalancerV2PoolTranslator" should "Create, update, and delete pool" in {
        // Create pool.
        val Boilerplate(_, _, _, lbId) = setUpLb(10)
        val poolId = createLbV2Pool(
            20, lbId,
            sessionPersistenceType = LBV2SessionPersistenceType.SOURCE_IP)
        eventually(checkPool(poolId, lbId))

        // Set admin state down.
        val poolJsonDown = lbV2PoolJson(id = poolId, loadBalancerId = lbId,
                                        adminStateUp = false)
        insertUpdateTask(30, LbV2PoolType, poolJsonDown, poolId)
        eventually(checkPool(poolId, lbId, adminStateUp = false))

        // Delete pool.
        insertDeleteTask(40, LbV2PoolType, poolId)
        eventually(checkNoPool(poolId))
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
        eventually {
            checkPool(poolId, lbId, memberIds = Seq(m1Id, m2Id, m3Id))
            checkPoolMember(m1Id, poolId, "10.0.0.11", 5001)
            checkPoolMember(m2Id, poolId, "10.0.0.12", 5002, weight = 2)
            checkPoolMember(m3Id, poolId, "10.0.0.12", 5003, weight = 3)
        }

        // Update one member's admin state and weight.
        val updatedM2Json = lbv2PoolMemberJson(
            id = m2Id, address = "10.0.0.12", protocolPort = 5002,
            weight = 10, adminStateUp = false, subnetId = snId, poolId = poolId)
        insertUpdateTask(60, LbV2PoolMemberType, updatedM2Json, m2Id)
        eventually {
            checkPool(poolId, lbId, memberIds = Seq(m1Id, m2Id, m3Id))
            checkPoolMember(m1Id, poolId, "10.0.0.11", 5001)
            checkPoolMember(m2Id, poolId, "10.0.0.12", 5002,
                            weight = 10, adminStateUp = false)
            checkPoolMember(m3Id, poolId, "10.0.0.12", 5003, weight = 3)
        }

        // Delete one member.
        insertDeleteTask(70, LbV2PoolMemberType, m3Id)
        eventually {
            checkPool(poolId, lbId, memberIds = Seq(m1Id, m2Id))
            checkPoolMember(m1Id, poolId, "10.0.0.11", 5001)
            checkPoolMember(m2Id, poolId, "10.0.0.12", 5002,
                            weight = 10, adminStateUp = false)
            checkNoPoolMember(m3Id)
        }

        // Delete the other two.
        insertDeleteTask(80, LbV2PoolMemberType, m1Id)
        insertDeleteTask(90, LbV2PoolMemberType, m2Id)
        eventually {
            checkPool(poolId, lbId, memberIds = Seq())
            checkNoPoolMember(m1Id)
            checkNoPoolMember(m2Id)
            checkNoPoolMember(m3Id)
        }
    }

    case class Boilerplate(nwId: UUID, snId: UUID, vipPortId: UUID, lbId: UUID)
    private def setUpLb(firstTaskId: Int): Boilerplate = {
        val nwId = createTenantNetwork(firstTaskId)
        val snId = createSubnet(firstTaskId + 1, nwId, "10.0.0.0/24")
        val vipPortId = createVipPort(firstTaskId + 2, nwId, snId, "10.0.0.1")
        val lbId = createLbV2(firstTaskId + 3, vipPortId, "10.0.0.1")
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
            pool.getSessionPersistence shouldBe sessionPersistence
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
