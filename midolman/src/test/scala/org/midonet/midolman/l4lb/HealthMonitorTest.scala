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
package org.midonet.midolman.l4lb

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import com.typesafe.config.ConfigFactory
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex
import org.junit.runner.RunWith
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.OneInstancePerTest
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.l4lb.HealthMonitor._
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class HealthMonitorTest extends MidolmanSpec
                                with TopologyBuilder
                                with MidonetEventually
                                with OneInstancePerTest
                                with MockitoSugar {

    var lock: InterProcessSemaphoreMutex = _
    var lockFactory: ZookeeperLockFactory = _
    var backend: MidonetBackend = _

    var healthMonitorUT: HealthMonitorUT = _
    var haproxyFakeActor: FakeHaproxy = _

    // Accounting
    var actorStarted = 0
    var actorStopped = 0
    var configUpdates = 0
    var routerAdded = 0
    var routerRemoved = 0

    val routerId = UUID.randomUUID()

    protected val backendCfg = new MidonetBackendConfig(
        ConfigFactory.parseString(""" zookeeper.root_key = '/' """))

    override def disableVtThreadCheck: Boolean = true

    class FakeHaproxy(config: PoolConfig, routerId: UUID, store: Storage,
                      hostId: UUID, lockFactory: ZookeeperLockFactory,
                      curator: CuratorFramework)
        extends HaproxyHealthMonitor(config, routerId, store, hostId,
            lockFactory, new SequenceDispenser(curator, backendCfg)) {

        var injectFailure = false

        override def doStart(): Unit = {
            actorStarted += 1
            notifyStarted()
        }
        override def doStop(): Unit = {
            actorStopped += 1
            notifyStopped()
        }
        override def updateConfig(conf: PoolConfig): Boolean = {
            configUpdates += 1
            !injectFailure
        }
        override def newRouter(id: UUID) = routerAdded += 1
        override def removeRouter() = routerRemoved += 1
    }

    class HealthMonitorUT(config: MidolmanConfig, backend: MidonetBackend,
                          lockFactory: ZookeeperLockFactory,
                          curator: CuratorFramework)
        extends HealthMonitor(config, backend, lockFactory, curator,
                              backendCfg) {

        def becomeLeader(): Unit = watcher.becomeHaproxyNode()

        def emitUpdate(update: HealthMonitorMessage): Unit =
            watcher.hmConfigBus onNext update

        override def getHaProxy(config: PoolConfig, routerId: UUID)
        : HaproxyHealthMonitor = {
            haproxyFakeActor = new FakeHaproxy(config, routerId, backend.store,
                                               hostId, lockFactory, curator)
            haproxyFakeActor
        }
    }

    override def beforeTest(): Unit = {
        lock = mock[InterProcessSemaphoreMutex]
        lockFactory = mock[ZookeeperLockFactory]
        Mockito.when(lockFactory.createShared(ZookeeperLockFactory.ZOOM_TOPOLOGY))
            .thenReturn(lock)
        Mockito.when(lock.acquire(anyLong(), anyObject())).thenReturn(true)
        backend = injector.getInstance(classOf[MidonetBackend])

        val config = injector.getInstance(classOf[MidolmanConfig])
        healthMonitorUT = new HealthMonitorUT(config, backend, lockFactory,
                                              mock[CuratorFramework])

        healthMonitorUT.startAsync().awaitRunning(10, TimeUnit.SECONDS)
        healthMonitorUT.becomeLeader()
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
            healthMonitorUT.emitUpdate(
                ConfigAdded(poolId, createFakePoolConfig(poolId),
                            routerId = null))

            Then ("The pool status should be updated to INACTIVE")
            eventually { getPoolStatus(poolId) shouldBe INACTIVE }

            And("The lock should have been acquired to perform this operation")
            verify (lock, times(1)).acquire(anyLong(), anyObject())
            And("The lock should have been released")
            verify (lock, times(1)).release()
        }

        scenario ("A config is added with no router and a disabled pool") {
            val poolId = UUID.randomUUID()
            storePool(poolId)

            When("A config is added with no router and admin state down")
            healthMonitorUT.emitUpdate(ConfigAdded(poolId,
                createFakePoolConfig(poolId, adminStateUp = false), routerId))

            Then ("The pool status should be updated to INACTIVE")
            eventually { getPoolStatus(poolId) shouldBe INACTIVE }
        }

        scenario("A config is added with an active pool") {
            val pool1Id = UUID.randomUUID()
            storePool(pool1Id)

            When("A config is added with an active pool")
            val config1 = createFakePoolConfig(pool1Id)
            healthMonitorUT.emitUpdate(ConfigAdded(pool1Id, config1, routerId))

            Then("Eventually an HA proxy is started")
            eventually {
                getPoolStatus(pool1Id) shouldBe ACTIVE
                actorStarted shouldBe 1
            }

            val pool2Id = UUID.randomUUID()
            storePool(pool2Id)
            val config2 = createFakePoolConfig(pool2Id)

            When("We send a config for the 1st pool again and one for a new pool")
            healthMonitorUT.emitUpdate(ConfigAdded(pool1Id, config1, routerId))
            healthMonitorUT.emitUpdate(ConfigAdded(pool2Id, config2, routerId))

            Then("Only two HA proxies should have been started")
            eventually {
                getPoolStatus(pool1Id) shouldBe ACTIVE
                getPoolStatus(pool2Id) shouldBe ACTIVE
                           actorStarted shouldBe 2
            }
        }
    }

    feature("HealthMonitor handles configuration updates correctly") {
        scenario ("Update a configuration with an HA proxy") {
            val poolId = UUID.randomUUID()
            storePool(poolId)
            val config = createFakePoolConfig(poolId)

            When ("We send a new configuration to the health monitor")
            healthMonitorUT.emitUpdate(ConfigAdded(poolId, config, routerId))

            Then("An haproxy actor is created")
            eventually { actorStarted shouldBe 1 }

            When("We send a configuration update")
            healthMonitorUT.emitUpdate(ConfigUpdated(poolId, config, routerId))

            Then("The new configuration should have been received")
            eventually { configUpdates shouldBe 1 }

            When("We send a new configuration with the pool disabled")
            haproxyFakeActor.injectFailure = true
            healthMonitorUT.emitUpdate(ConfigUpdated(poolId,
                createFakePoolConfig(poolId, adminStateUp = false), routerId))

            Then("Eventually the HA proxy should be stopped")
            eventually { actorStopped shouldBe 1 }
        }

        scenario ("Update a configuration with no HA proxy") {
            val poolId = UUID.randomUUID()
            storePool(poolId)
            val config = createFakePoolConfig(poolId)

            When ("An update is sent with a null router ID")
            healthMonitorUT.emitUpdate(ConfigUpdated(poolId, config,
                                                     routerId = null))

            Then("The status of the pool should be set to inactive")
            eventually { getPoolStatus(poolId) shouldBe INACTIVE }

            When("An update is sent with a non-null router ID")
            healthMonitorUT.emitUpdate(ConfigUpdated(poolId, config, routerId))

            Then("Eventually an HA proxy should be started")
            eventually {actorStarted shouldBe 1 }

            When("We send an update with a disabled pool")
            haproxyFakeActor.injectFailure = true
            healthMonitorUT.emitUpdate(ConfigUpdated(poolId,
                createFakePoolConfig(poolId, adminStateUp = false), routerId))

            Then("Eventually the HA proxy should be stopped")
            eventually { actorStopped shouldBe 1 }
        }
    }

    feature("HealthMonitor handles configuration deletes correctly") {
        scenario("Sending a config delete with no HA proxy") {
            val poolId = UUID.randomUUID()
            storePool(poolId)

            When("We send a config delete message")
            healthMonitorUT.emitUpdate(ConfigDeleted(poolId))

            Then("Eventually the pool is disabled")
            eventually { getPoolStatus(poolId) shouldBe INACTIVE }
        }

        scenario("Sending a config delete with an HA proxy") {
            val poolId = UUID.randomUUID()
            storePool(poolId)

            When("We send a config add")
            healthMonitorUT.emitUpdate(ConfigAdded(poolId,
                createFakePoolConfig(poolId), routerId))

            Then("Eventually an HA proxy is started")
            eventually { actorStarted shouldBe 1 }

            When("We send a config delete message")
            healthMonitorUT.emitUpdate(ConfigDeleted(poolId))

            Then("Eventually the pool is disabled")
            eventually { actorStopped shouldBe 1 }
        }
    }

    feature ("HealthMonitor handles changes in the router") {
        scenario("A router is added") {
            When("We send a router added message")
            val poolId = UUID.randomUUID()
            healthMonitorUT.emitUpdate(
                RouterChanged(poolId, createFakePoolConfig(poolId), routerId))

            Then("Eventually an HA proxy is started")
            eventually { actorStarted shouldBe 1 }
        }

        scenario ("A config and then a router is added") {
            Given ("A config")
            val poolId = UUID.randomUUID()
            healthMonitorUT.emitUpdate(ConfigAdded(poolId,
                                                   createFakePoolConfig(poolId),
                                                   routerId))
            eventually { actorStarted shouldBe 1 }

            When ("The router is added")
            healthMonitorUT.emitUpdate(RouterChanged(poolId,
                                                     createFakePoolConfig(poolId),
                                                     routerId))

            Then ("The RouterAdded msg should be sent")
            eventually { routerAdded shouldBe 1 }
        }

        scenario ("A router is deleted") {
            Given ("A config with a router")
            val poolId = UUID.randomUUID()
            val routerId = UUID.randomUUID()
            healthMonitorUT.emitUpdate(ConfigAdded(poolId,
                                                   createFakePoolConfig(poolId),
                                                   routerId))

            eventually { actorStarted shouldBe 1 }

            When ("The router is deleted")
            healthMonitorUT.emitUpdate(RouterChanged(poolId,
                                                     createFakePoolConfig(poolId),
                                                     routerId = null))

            Then ("The RouterRemoved msg should be sent")
            eventually { routerRemoved shouldBe 1 }
        }

        scenario ("A router is updated with an inactive pool") {
            When ("A router is updated")
            val poolId = UUID.randomUUID()
            storePool(poolId)
            healthMonitorUT.emitUpdate(RouterChanged(poolId,
                                                     createFakePoolConfig(poolId),
                                                     routerId = null))

            Then ("The state should be set to INACTIVE")
            eventually { getPoolStatus(poolId) shouldBe INACTIVE }
        }
    }

    def createFakePoolConfig(poolId: UUID, adminStateUp: Boolean = true)
    : PoolConfig = {
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
}
