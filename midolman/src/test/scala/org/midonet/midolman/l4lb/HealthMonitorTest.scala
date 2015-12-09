/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.midolman.l4lb

import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import akka.actor.{Actor, ActorRef, Props}
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.listen.Listenable
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex
import org.apache.curator.framework.state.ConnectionStateListener
import org.junit.runner.RunWith
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus
import org.mockito.Matchers._
import org.mockito.Mockito
import org.scalatest.OneInstancePerTest
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.l4lb.HaproxyHealthMonitor.{ConfigUpdate, RouterAdded, RouterRemoved}
import org.midonet.midolman.l4lb.HealthMonitor.{ConfigDeleted, ConfigAdded, ConfigUpdated}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class HealthMonitorTest extends MidolmanSpec
                                with TopologyBuilder
                                with MidonetEventually
                                with OneInstancePerTest
                                with MockitoSugar {

    // we just need a no-op actor to act as the manager for the
    // HaproxyHealthMonitor
    class HaproxyFakeActor extends Actor {
        override def preStart(): Unit = {
            newActors += 1
        }
        def receive = {
            case ConfigUpdate(conf) => configUpdates += 1
            case RouterAdded(id) => routerAdded += 1
            case RouterRemoved => routerRemoved += 1
        }

        override def postStop(): Unit = {
            actorStopped += 1
        }
    }

    var healthMonitorUT: ActorRef = _
    var haproxyFakeActor: ActorRef = _
    var backend: MidonetBackend = _

    //Accounting
    var newActors = 0
    var actorStopped = 0
    var configUpdates = 0
    var routerAdded = 0
    var routerRemoved = 0

    override def beforeTest(): Unit = {
        backend = injector.getInstance(classOf[MidonetBackend])

        val config = injector.getInstance(classOf[MidolmanConfig])
        val lockFactory = mock[ZookeeperLockFactory]
        val curator = mock[CuratorFramework]
//        Mockito.when(curator.getConnectionStateListenable)
//               .thenReturn(mock[Listenable[ConnectionStateListener]])

        val lock = mock[InterProcessSemaphoreMutex]
        Mockito.when(lockFactory.createShared(ZookeeperLockFactory.ZOOM_TOPOLOGY))
               .thenReturn(lock)
        Mockito.when(lock.acquire(anyLong(), anyObject())).thenReturn(true)

        healthMonitorUT = actorSystem.actorOf(
            Props(new HealthMonitorUT(config, backend, lockFactory, curator))
        )
    }

    private def storePool(poolId: UUID): Pool = {
        val pool = createPool(id = poolId)
        backend.store.create(pool)
        pool
    }

    private def getPoolStatus(id: UUID): PoolHealthMonitorMappingStatus = {
        val pool = Await.result(backend.store.get(classOf[Pool], id),
                                Duration.Inf)
        pool.getMappingStatus
    }

    feature("HealthMonitor handles added configurations correctly") {
        scenario ("A config is added with no router") {
            val poolId = UUID.randomUUID()
            storePool(poolId)

            When ("A config is added with no router")
            healthMonitorUT ! ConfigAdded(poolId,
                  createFakePoolConfig(poolId, adminStateUp = true),
                                       routerId = null)

            Then ("The pool status should be updated to INACTIVE")
            eventually { getPoolStatus(poolId) shouldBe INACTIVE }
        }

        scenario ("A config is added with no router and a disabled pool") {
            val poolId = UUID.randomUUID()
            storePool(poolId)
            val routerId = UUID.randomUUID()

            When("A config is added with no router and admin state down")
            healthMonitorUT ! ConfigAdded(poolId,
                createFakePoolConfig(poolId, adminStateUp = false), routerId)

            Then ("The pool status should be updated to INACTIVE")
            eventually { getPoolStatus(poolId) shouldBe INACTIVE }
        }

        scenario("A config is added with an active pool") {
            val pool1Id = UUID.randomUUID()
            storePool(pool1Id)
            val routerId = UUID.randomUUID()

            When("A config is added with an active pool")
            val config1 = createFakePoolConfig(pool1Id, adminStateUp = true)
            healthMonitorUT ! ConfigAdded(pool1Id, config1, routerId)

            Then("Eventually an HA proxy is started")
            eventually {
                getPoolStatus(pool1Id) shouldBe ACTIVE
                newActors shouldBe 1
            }

            val pool2Id = UUID.randomUUID()
            storePool(pool2Id)
            val config2 = createFakePoolConfig(pool2Id, adminStateUp = true)

            When("We send a config for the 1st pool again and one for a new pool")
            healthMonitorUT ! ConfigAdded(pool1Id, config1, routerId)
            healthMonitorUT ! ConfigAdded(pool2Id, config2, routerId)

            Then("Only two HA proxies should have been started")
            eventually {
                getPoolStatus(pool1Id) shouldBe ACTIVE
                getPoolStatus(pool2Id) shouldBe ACTIVE
                newActors shouldBe 2
            }
        }
    }

    feature("HealthMonitor handles configuration updates correctly") {
        scenario ("Update a configuration with an HA proxy") {
            val poolId = UUID.randomUUID()
            storePool(poolId)
            val config = createFakePoolConfig(poolId, adminStateUp = true)
            val routerId = UUID.randomUUID()

            When ("We send a new configuration to the health monitor")
            healthMonitorUT ! ConfigAdded(poolId, config, routerId)

            Then("An haproxy actor is created")
            eventually { newActors shouldBe 1 }

            When("We send a configuration update")
            healthMonitorUT ! ConfigUpdated(poolId, config, routerId)

            Then("The new configuration should have been received")
            eventually { configUpdates shouldBe 1 }

            When("We send a new configuration with the pool disabled")
            healthMonitorUT ! ConfigUpdated(poolId,
                createFakePoolConfig(poolId, adminStateUp = false), routerId)

            Then("Eventually the HA proxy should be stopped")
            eventually { actorStopped shouldBe 1 }
        }

        scenario ("Update a configuration with no HA proxy") {
            val poolId = UUID.randomUUID()
            storePool(poolId)
            val config = createFakePoolConfig(poolId, adminStateUp = true)

            When ("An update is sent with a null router ID")
            healthMonitorUT ! ConfigUpdated(poolId, config, routerId = null)

            Then("The status of the pool should be set to inactive")
            eventually { getPoolStatus(poolId) shouldBe INACTIVE }

            val routerId = UUID.randomUUID()
            When("An update is sent with a non-null router ID")
            healthMonitorUT ! ConfigUpdated(poolId, config, routerId)

            Then("Eventually an HA proxy should be started")
            eventually { newActors shouldBe 1 }

            When("We send an update with a disabled pool")
            healthMonitorUT ! ConfigUpdated(poolId,
                createFakePoolConfig(poolId, adminStateUp = false), routerId)

            Then("Eventually the HA proxy should be stopped")
            eventually { actorStopped shouldBe 1 }
        }
    }

    feature("HealthMonitor handles configuration deletes correctly") {
        scenario("Sending a config delete with no HA proxy") {
            val poolId = UUID.randomUUID()
            storePool(poolId)

            When("We send a confg delete message")
            healthMonitorUT ! ConfigDeleted(poolId)

            Then("Eventually the pool is disabled")
            eventually { getPoolStatus(poolId) shouldBe INACTIVE }
        }

        scenario("Sending a config delete with an HA proxy") {
            val poolId = UUID.randomUUID()
            storePool(poolId)

            When("We send a config add")
            healthMonitorUT ! ConfigAdded(poolId,
                createFakePoolConfig(poolId, adminStateUp = true),
                routerId = UUID.randomUUID())

            Then("Eventually an HA proxy is started")
            eventually { newActors shouldBe 1 }

            When("We send a config delete message")
            healthMonitorUT ! ConfigDeleted(poolId)

            Then("Eventually the pool is disabled")
            eventually { actorStopped shouldBe 1 }
        }
    }

//    feature ("HealthMonitor handles changes in the router") {
//        scenario ("a router is deleted") {
//            Given ("a config with a router")
//            healthMonitorUT ! ConfigAdded(poolId, createFakePoolConfig(true),
//                                          UUID.randomUUID())
//            eventually (timeout(Span(3, Seconds))) { newActors should be (1) }
//            When ("the router is deleted")
//            healthMonitorUT ! RouterChanged(poolId, createFakePoolConfig(true),
//                                            null)
//            Then ("the RouterRemoved msg should be sent")
//            eventually (timeout(Span(3, Seconds)))
//                { routerRemoved should be (1) }
//        }
//        scenario ("a router is added") {
//            Given ("a config associated with a instance")
//            healthMonitorUT ! ConfigAdded(poolId, createFakePoolConfig(true),
//                                          UUID.randomUUID())
//            eventually (timeout(Span(3, Seconds))) { newActors should be (1) }
//            When ("the router is added")
//            healthMonitorUT ! RouterChanged(poolId, createFakePoolConfig(true),
//                                            UUID.randomUUID())
//            Then ("the RouterAdded msg should be sent")
//            eventually (timeout(Span(3, Seconds))) { routerAdded should be (1) }
//        }
//        scenario ("a router is updated on a non-existent instance") {
//            When ("a router is updated")
//            healthMonitorUT ! RouterChanged(poolId, createFakePoolConfig(true),
//                                            null)
//            Then ("The state should be set to INACTIVE")
//            mverify(mockClient, mtimeo(100).times(1)).poolSetMapStatus(poolId,
//                PoolHealthMonitorMappingStatus.INACTIVE)
//        }
//    }

    def createFakePoolConfig(poolId: UUID, adminStateUp: Boolean): PoolConfig = {
        val vip = new VipConfig(true, UUID.randomUUID(), "9.9.9.9", 89, null)
        val healthMonitor = new HealthMonitorConfig(true, 5, 10, 7)
        val member1  = new PoolMemberConfig(true, UUID.randomUUID(),
                                            10, "10.11.12.13", 81)
        val member2  = new PoolMemberConfig(true, UUID.randomUUID(),
                                            10, "10.11.12.14", 81)
        val member3  = new PoolMemberConfig(true, UUID.randomUUID(),
                                            10, "10.11.12.15", 81)
        new PoolConfig(poolId, loadBalancerId = UUID.randomUUID(), Set(vip),
               Set(member1, member2, member3), healthMonitor, adminStateUp, "",
               "_MN")
    }

    /*
     * This is a testable version of the HaproxyHealthMonitor. This overrides
     * the functions that would block and perform IO.
     */
    class HealthMonitorUT(config: MidolmanConfig, backend: MidonetBackend,
                          lockFactory: ZookeeperLockFactory,
                          curator: CuratorFramework)
        extends HealthMonitor(config, backend, lockFactory, curator) {

        override def startChildHaproxyMonitor(poolId: UUID, config: PoolConfig,
                                              routerId: UUID) = {
            haproxyFakeActor = context.actorOf(
                Props(new HaproxyFakeActor), poolId.toString)
            haproxyFakeActor
        }
    }
}
