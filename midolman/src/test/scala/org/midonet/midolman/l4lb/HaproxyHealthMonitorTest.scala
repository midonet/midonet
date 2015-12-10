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

import java.nio.channels.spi.SelectorProvider
import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import akka.actor.{Actor, ActorRef, Props}
import org.junit.runner.RunWith
import org.midonet.cluster.topology.TopologyBuilder
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.models.Topology.Pool.{PoolHealthMonitorMappingStatus => PoolStatus}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.l4lb.HaproxyHealthMonitor.SetupFailure
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.netlink.UnixDomainChannel
import org.midonet.util.AfUnix.Address
import org.midonet.util.{AfUnix, MidonetEventually}

@RunWith(classOf[JUnitRunner])
class HaproxyHealthMonitorTest extends MidolmanSpec
                                       with OneInstancePerTest
                                       with MockitoSugar
                                       with MidonetEventually
                                       with TopologyBuilder {

    import org.midonet.midolman.l4lb.HaproxyHealthMonitor.SockReadFailure

    // A mock actor playing the role of the health monitor
    class Manager extends Actor {
        def receive = {
            case SockReadFailure =>
                sockReadFailures += 1
            case SetupFailure =>
                setupFailures += 1
            case x =>
        }
    }

    // command handler for a "working" socket.
    var healthMonitorUT: ActorRef = _
    // command handler for a "non-working" socket.
    var managerActor: ActorRef = _
    var store: Storage = _

    val lockFactory = mock[ZookeeperLockFactory]
    val poolId = UUID.randomUUID()

    // Accounting variables to keep track of events that happen
    var confWrites = 0
    var socketReads = 0
    var haproxyRestarts = 0
    var lastIpWritten: String = _
    var sockReadFailures = 0
    var setupFailures = 0
    var failUpdate = false

    // Special IP to cause a (fake) delay in the config write
    val DelayedIp = "11.11.11.11"
    val NormalIp = "12.12.12.12"
    val badSocketPath = "bad/socket/path"
    val goodSocketPath = "/etc/midolman/l4lb/"

    def createFakePoolConfig(vipIp: String, path: String) = {
        val vip = new VipConfig(true, UUID.randomUUID(), vipIp, 89, null)
        val healthMonitor = new HealthMonitorConfig(true, 5, 10, 7)
        val member1  = new PoolMemberConfig(true, UUID.randomUUID(),
                                            10, "10.11.12.13", 81)
        val member2  = new PoolMemberConfig(true, UUID.randomUUID(),
                                            10, "10.11.12.14", 81)
        val member3  = new PoolMemberConfig(true, UUID.randomUUID(),
                                            10, "10.11.12.15", 81)

        new PoolConfig(poolId, UUID.randomUUID(), Set(vip),
                       Set(member1, member2, member3), healthMonitor,
                       adminStateUp = true, path, "_MN")
    }

    override def beforeTest(): Unit = {
        store = injector.getInstance(classOf[MidonetBackend]).store
        managerActor = actorSystem.actorOf(Props(new Manager))
        healthMonitorUT = actorSystem.actorOf(Props(new HaproxyHealthMonitorUT(
            createFakePoolConfig("10.10.10.10", goodSocketPath),
            managerActor, routerId = UUID.randomUUID(),
            store, hostId = UUID.randomUUID(), lockFactory)))
    }

    override def afterTest(): Unit = {
        reset(lockFactory)
    }

    private def storePool(poolId: UUID): Pool = {
        val pool = createPool(id = poolId)
        store.create(pool)
        pool
    }

    private def getPoolStatus(poolId: UUID): PoolStatus = {
        val pool = Await.result(store.get(classOf[Pool], poolId),
                                Duration.Inf)
        pool.getMappingStatus
    }

    feature("HaproxyHealthMonitor writes its config file") {
        scenario ("Actor Start Up") {
            storePool(poolId)

            When("The HaproxyHealthMonitor starts up")
            Then ("Eventually a config write should happen")
            eventually {
               confWrites shouldBe 1
               haproxyRestarts shouldBe 1
               getPoolStatus(poolId) shouldBe ACTIVE

                // TODO: remove mock of ha proxy to verify more things.
            }
        }

//        scenario ("Config change") {
//            When ("We change the config of haproxy")
//            healthMonitorUT ! ConfigUpdate(createFakePoolConfig("10.10.10.10",
//                goodSocketPath))
//            Then ("The config write should happen again")
//            confWrites should be (2)
//            And ("Haproxy should have been restarted")
//            haproxyRestarts should be (1)
//            verify(mockClient, times(2)).poolSetMapStatus(
//                poolId, PoolHealthMonitorMappingStatus.ACTIVE)
//        }
//        scenario ("Config write is delayed") {
//            When ("The config takes a long time to be written")
//            healthMonitorUT ! ConfigUpdate(createFakePoolConfig(DelayedIp,
//                goodSocketPath))
//            And ("Another config is immediately written")
//            healthMonitorUT ! ConfigUpdate(createFakePoolConfig(NormalIp,
//                goodSocketPath))
//            Then ("The last IP written should be the last config sent")
//            lastIpWritten should equal (NormalIp)
//            verify(mockClient, times(3)).poolSetMapStatus(
//                poolId, PoolHealthMonitorMappingStatus.ACTIVE)
//
//            And ("The there is a problem with the update")
//            failUpdate = true
//            healthMonitorUT ! ConfigUpdate(createFakePoolConfig(NormalIp,
//                goodSocketPath))
//            Then ("The status should have been set to ERROR")
//            confWrites should be (4)
//            setupFailures should be (1)
//            verify(mockClient, times(3)).poolSetMapStatus(
//                poolId, PoolHealthMonitorMappingStatus.ACTIVE)
//            verify(mockClient, times(1)).poolSetMapStatus(
//                poolId, PoolHealthMonitorMappingStatus.ERROR)
//
//            failUpdate = false
//        }
    }

//    feature("HaproxyHealthMonitor handles socket reads") {
//        scenario ("HaproxyHealthMonitor reads the haproxy socket") {
//            When ("HaproxyHealthMonitor is started")
//            Then ("then socket should read.")
//            eventually { socketReads should be > 0 }
//            verify(mockClient, times(1)).poolSetMapStatus(
//                poolId, PoolHealthMonitorMappingStatus.ACTIVE)
//        }
//        scenario ("HaproxyHealthMonitor fails to read a socket") {
//            When (" A bad socket path is sent to HaproxyHealthMonitor")
//            healthMonitorUT ! ConfigUpdate(createFakePoolConfig("10.10.10.10",
//                                                                badSocketPath))
//            Then ("The manager should receive a failure notification")
//            eventually { sockReadFailures should be > 0 }
//            verify(mockClient, times(1)).poolSetMapStatus(
//                poolId, PoolHealthMonitorMappingStatus.ERROR)
//        }
//    }

    /*
     * A fake unix channel that will do nothing.
     */
    class MockUnixChannel(provider: SelectorProvider)
        extends UnixDomainChannel(provider: SelectorProvider, AfUnix.Type.SOCK_STREAM) {

        override def connect(address: AfUnix.Address): Boolean = true
        override def implConfigureBlocking(block: Boolean) = {}
        override def _executeConnect(address: Address) = {}
        override def closeFileDescriptor() = {}
    }

    /*
     * This is a testable version of the HaproxyHealthMonitor. This overrides
     * the functions that would block and perform IO.
     */
    class HaproxyHealthMonitorUT(config: PoolConfig,
                                 manager: ActorRef,
                                 routerId: UUID,
                                 store: Storage,
                                 hostId: UUID,
                                 lockFactory: ZookeeperLockFactory)
        extends HaproxyHealthMonitor(config: PoolConfig,
                                     manager: ActorRef,
                                     routerId: UUID,
                                     store: Storage,
                                     hostId: UUID,
                                     lockFactory: ZookeeperLockFactory) {

        override def makeChannel() = new MockUnixChannel(null)
        override def writeConf(config: PoolConfig): Unit = {
            if (config.vip.ip == DelayedIp) {
                Thread.sleep(2000)
            }
            confWrites +=1
            lastIpWritten = config.vip.ip
        }
        override def restartHaproxy(name: String, confFileLoc: String,
                                    pidFileLoc: String) = {
            haproxyRestarts += 1
        }
        override def createNamespace(name: String, ip: String): String = {""}
        override def getHaproxyStatus(path: String) : String = {
            if (path.contains(badSocketPath)) {
                throw new Exception
            }
            socketReads +=1
            "" // return empty string because it isn't checked
        }
        override def hookNamespaceToRouter() = {}
        override def unhookNamespaceFromRouter = {}
        override def startHaproxy(name: String) = {
            if (failUpdate) {
                throw new Exception
            }
            0
        }
        override def killHaproxyIfRunning(name: String, confFileLoc: String,
                                          pidFileLoc: String) = {}
    }
}
