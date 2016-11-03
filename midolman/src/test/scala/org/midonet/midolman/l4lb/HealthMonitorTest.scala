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

import java.io.{File, FileWriter}
import java.util.{LinkedList, UUID}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import akka.actor.{Actor, ActorRef, Props}

import com.typesafe.config.{Config, ConfigFactory}

import org.apache.curator.framework.CuratorFramework
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.OneInstancePerTest
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.l4lb.HaproxyHealthMonitor.{ConfigUpdate, RouterAdded, RouterRemoved, SetupFailure}
import org.midonet.midolman.l4lb.HealthMonitor.{ConfigAdded, ConfigDeleted, ConfigUpdated, RouterChanged}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class HealthMonitorTest extends MidolmanSpec
                                with TopologyBuilder
                                with MidonetEventually
                                with OneInstancePerTest
                                with MockitoSugar {

    // A no-op actor to act as a mock of the HaproxyHealthMonitor
    class HaproxyFakeActor extends Actor {
        override def preStart(): Unit = {
            actorStarted += 1
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

    // Accounting
    var actorStarted = 0
    var actorStopped = 0
    var configUpdates = 0
    var routerAdded = 0
    var routerRemoved = 0

    override def beforeTest(): Unit = {
        backend = injector.getInstance(classOf[MidonetBackend])

        val config = injector.getInstance(classOf[MidolmanConfig])
        val curator = mock[CuratorFramework]

        HealthMonitor.ipCommand = mock[IP]
        setupIpMock(HealthMonitor.ipCommand)

        healthMonitorUT = actorSystem.actorOf(
            Props(new HealthMonitorUT(config, backend, curator))
        )
    }

    override def fillConfig(config: Config): Config = {
        val conf = super.fillConfig(config)
        val defaults =
            """
              |agent.haproxy_health_monitor.namespace_cleanup = true
              |agent.haproxy_health_monitor.haproxy_file_loc = "/tmp"
            """.stripMargin

        conf.withFallback(ConfigFactory.parseString(defaults))
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

    def sendMsgToHealthMonitor(msg: Any): Unit =
        healthMonitorUT.tell(msg, haproxyFakeActor)

    private def setupIpMock(mock: IP): Unit = {
        val namespaces = new LinkedList[String]()
        val namespace = "namespace_hm"
        namespaces.add(namespace)
        Mockito.when(mock.execGetOutput("ip netns")).thenReturn(namespaces)
        Mockito.when(mock.namespaceExist(namespace)).thenReturn(true)
        Mockito.when(mock.interfaceExistsInNs(namespace, namespace + "_dp"))
               .thenReturn(true)
    }

    private def checkCleanupNamespaces(): Unit = {
        val mock = HealthMonitor.ipCommand
        verify(mock).exec("ip link delete namespace_hm_dp")
        verify(mock).deleteNS("namespace_hm")
    }

    feature("HealthMonitor handles added configurations correctly") {
        scenario ("A config is added with no router") {
            val poolId = UUID.randomUUID()
            storePool(poolId)

            When ("A config is added with no router")
            healthMonitorUT ! ConfigAdded(poolId,
                  createFakePoolConfig(poolId), routerId = null)

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
            val config1 = createFakePoolConfig(pool1Id)
            healthMonitorUT ! ConfigAdded(pool1Id, config1, routerId)

            Then("Eventually an HA proxy is started")
            eventually {
                getPoolStatus(pool1Id) shouldBe ACTIVE
                           actorStarted shouldBe 1

                checkCleanupNamespaces()
            }

            val pool2Id = UUID.randomUUID()
            storePool(pool2Id)
            val config2 = createFakePoolConfig(pool2Id)

            When("We send a config for the 1st pool again and one for a new pool")
            healthMonitorUT ! ConfigAdded(pool1Id, config1, routerId)
            healthMonitorUT ! ConfigAdded(pool2Id, config2, routerId)

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
            val routerId = UUID.randomUUID()

            When ("We send a new configuration to the health monitor")
            healthMonitorUT ! ConfigAdded(poolId, config, routerId)

            Then("An haproxy actor is created")
            eventually {actorStarted shouldBe 1 }

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
            val config = createFakePoolConfig(poolId)

            When ("An update is sent with a null router ID")
            healthMonitorUT ! ConfigUpdated(poolId, config, routerId = null)

            Then("The status of the pool should be set to inactive")
            eventually { getPoolStatus(poolId) shouldBe INACTIVE }

            val routerId = UUID.randomUUID()
            When("An update is sent with a non-null router ID")
            healthMonitorUT ! ConfigUpdated(poolId, config, routerId)

            Then("Eventually an HA proxy should be started")
            eventually {actorStarted shouldBe 1 }

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

            When("We send a config delete message")
            healthMonitorUT ! ConfigDeleted(poolId)

            Then("Eventually the pool is disabled")
            eventually { getPoolStatus(poolId) shouldBe INACTIVE }
        }

        scenario("Sending a config delete with an HA proxy") {
            val poolId = UUID.randomUUID()
            storePool(poolId)

            When("We send a config add")
            healthMonitorUT ! ConfigAdded(poolId,
                createFakePoolConfig(poolId),
                routerId = UUID.randomUUID())

            Then("Eventually an HA proxy is started")
            eventually {actorStarted shouldBe 1 }

            When("We send a config delete message")
            healthMonitorUT ! ConfigDeleted(poolId)

            Then("Eventually the pool is disabled")
            eventually { actorStopped shouldBe 1 }
        }
    }

    feature ("HealthMonitor handles changes in the router") {
        scenario("A router is added") {
            When("We send a router added message")
            val poolId = UUID.randomUUID()
            healthMonitorUT ! RouterChanged(poolId,
                                            createFakePoolConfig(poolId),
                                            routerId = UUID.randomUUID())

            Then("Eventually an HA proxy is started")
            eventually {actorStarted shouldBe 1 }
        }

        scenario ("A config and then a router is added") {
            Given ("A config")
            val poolId = UUID.randomUUID()
            healthMonitorUT ! ConfigAdded(poolId, createFakePoolConfig(poolId),
                                          routerId = UUID.randomUUID())
            eventually {actorStarted shouldBe 1 }

            When ("The router is added")
            healthMonitorUT ! RouterChanged(poolId, createFakePoolConfig(poolId),
                                            routerId = UUID.randomUUID())

            Then ("The RouterAdded msg should be sent")
            eventually { routerAdded shouldBe 1 }
        }

        scenario ("A router is deleted") {
            Given ("A config with a router")
            val poolId = UUID.randomUUID()
            val routerId = UUID.randomUUID()
            healthMonitorUT ! ConfigAdded(poolId, createFakePoolConfig(poolId),
                                          routerId)

            eventually {actorStarted shouldBe 1 }

            When ("The router is deleted")
            healthMonitorUT ! RouterChanged(poolId, createFakePoolConfig(poolId),
                                            routerId = null)

            Then ("The RouterRemoved msg should be sent")
            eventually { routerRemoved shouldBe 1 }
        }



        scenario ("A router is updated with an inactive pool") {
            When ("A router is updated")
            val poolId = UUID.randomUUID()
            storePool(poolId)
            healthMonitorUT ! RouterChanged(poolId,
                                            createFakePoolConfig(poolId),
                                            routerId = null)

            Then ("The state should be set to INACTIVE")
            eventually { getPoolStatus(poolId) shouldBe INACTIVE }
        }
    }

    feature("Other messages are handled properly") {
        scenario("Setup failure") {
            val poolId = UUID.randomUUID()

            When("We send a config added message")
            healthMonitorUT ! ConfigAdded(poolId, createFakePoolConfig(poolId),
                                          routerId = UUID.randomUUID())

            Then("Eventually an HA proxy is started")
            eventually {actorStarted shouldBe 1 }

            When("We send a setup failure message")
            sendMsgToHealthMonitor(SetupFailure)

            Then("Eventually the HA proxy is stopped")
            eventually { actorStopped shouldBe 1 }
        }

        scenario("SockRead failure") {
            val poolId = UUID.randomUUID()

            When("We send a config added message")
            healthMonitorUT ! ConfigAdded(poolId, createFakePoolConfig(poolId),
                                          routerId = UUID.randomUUID())

            Then("Eventually an HA proxy is started")
            eventually {actorStarted shouldBe 1 }

            When("We send a socket read failure message")
            sendMsgToHealthMonitor(SetupFailure)

            Then("Eventually the HA proxy is stopped")
            eventually { actorStopped shouldBe 1 }
        }
    }

    feature("Public methods") {
        scenario("cleanAndDeleteNamespace") {
            When("We stop all actors")
            actorSystem.shutdown()

            Then("namespaces are cleaned up")
            eventually { checkCleanupNamespaces() }
        }

        scenario("isRunningHaProxy") {
            // Only the negative case can be tested
            HealthMonitor.isRunningHaproxyPid(pid = -1,
                pidFilePath = "somepath",
                confFilePath = "someotherpath") shouldBe false

        }

        scenario("getHaproxyPid") {
            HealthMonitor.getHaproxyPid("incorrectPath") shouldBe None

            val filePath = "/tmp/pid-file"
            var fileWriter = new FileWriter(filePath, false /* append */)
            fileWriter.write("notAPID")
            fileWriter.close()
            HealthMonitor.getHaproxyPid(filePath) shouldBe None

            fileWriter = new FileWriter(filePath, false /* append */)
            fileWriter.write("1234")
            fileWriter.close()

            HealthMonitor.getHaproxyPid(filePath) shouldBe Some(1234)

            val file = new File(filePath)
            file.delete()
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
               Set(member1, member2, member3), healthMonitor, adminStateUp,
               "", "_MN")
    }

    protected val backendCfg = new MidonetBackendConfig(
        ConfigFactory.parseString(""" zookeeper.root_key = '/' """))

    /*
     * This is a testable version of the HaproxyHealthMonitor. This overrides
     * the functions that would block and perform IO.
     */
    class HealthMonitorUT(config: MidolmanConfig, backend: MidonetBackend,
                          curator: CuratorFramework)
        extends HealthMonitor(config, backend, curator, backendCfg) {

        override def startChildHaproxyMonitor(poolId: UUID, config: PoolConfig,
                                              routerId: UUID) = {
            haproxyFakeActor = context.actorOf(
                Props(new HaproxyFakeActor), poolId.toString)
            haproxyFakeActor
        }
    }
}
