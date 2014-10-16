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

import akka.actor.{Actor, ActorRef, Props, ActorSystem}
import akka.testkit._
import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.mockito.Mockito.{verify, reset, times}
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Span, Seconds}

import org.midonet.cluster.{LocalDataClientImpl, DataClient, LocalClientImpl}
import org.midonet.midolman.l4lb.HaproxyHealthMonitor.SetupFailure
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus
import org.midonet.netlink.AfUnix.Address
import org.midonet.netlink.{AfUnix, UnixDomainChannel}

@RunWith(classOf[JUnitRunner])
class HaproxyHealthMonitorTest extends TestKit(ActorSystem("HaproxyActorTest"))
                               with ImplicitSender
                               with FeatureSpecLike
                               with Matchers
                               with GivenWhenThen
                               with BeforeAndAfter
                               with OneInstancePerTest
                               with MockitoSugar {

    import HaproxyHealthMonitor.SockReadFailure
    import HaproxyHealthMonitor.ConfigUpdate

    case object MonitorActorUp

    val testKit = self

    // we just need a no-op actor to act as the manager for the
    // HaproxyHealthMonitor
    class Manager extends Actor {
        def receive = {
            case SockReadFailure =>
                sockReadFailures += 1
            case SetupFailure =>
                setupFailures += 1
            case MonitorActorUp =>
                testKit forward MonitorActorUp
            case x =>
        }
    }

    // command handler for a "working" socket.
    var healthMonitorUT: ActorRef = _
    // command handler for a "non-working" socket.
    var managerActor: ActorRef = _
    val poolId = UUID.randomUUID()
    var mockClient = mock[LocalDataClientImpl]

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
                       Set(member1, member2, member3), healthMonitor, true,
                       path, "_MN")
    }

    before {
        managerActor = system.actorOf(Props(new Manager))
        healthMonitorUT = system.actorOf(
            Props(new HaproxyHealthMonitorUT(
                createFakePoolConfig("10.10.10.10", goodSocketPath),
                managerActor, UUID.randomUUID(),
                mockClient, UUID.randomUUID())))
        expectMsg(MonitorActorUp)
    }

    after {
        reset(mockClient)
    }

    feature("HaproxyHealthMonitor writes its config file") {
        scenario ("Actor Start Up") {
            When("The HaproxyHealthMonitor starts up")
            Then ("A config write should happen once")
            eventually { confWrites should be (1) }
            haproxyRestarts should be (1)
            verify(mockClient, times(1)).poolSetMapStatus(poolId,
                    PoolHealthMonitorMappingStatus.ACTIVE)
        }
        scenario ("Config change") {
            When ("We change the config of haproxy")
            healthMonitorUT ! ConfigUpdate(createFakePoolConfig("10.10.10.10",
                goodSocketPath))
            Then ("The config write should happen again")
            eventually { confWrites should be (2) }
            And ("Haproxy should have been restarted")
            eventually { haproxyRestarts should be (1) }
            verify(mockClient, times(2)).poolSetMapStatus(poolId,
                PoolHealthMonitorMappingStatus.ACTIVE)
        }
        scenario ("Config write is delayed") {
            When ("The config takes a long time to be written")
            healthMonitorUT ! ConfigUpdate(createFakePoolConfig(DelayedIp,
                goodSocketPath))
            And ("Another config is immediately written")
            healthMonitorUT ! ConfigUpdate(createFakePoolConfig(NormalIp,
                goodSocketPath))
            Then ("The last IP written should be the last config sent")
            eventually (timeout(Span(3, Seconds)))
                { lastIpWritten should equal (NormalIp) }
            verify(mockClient, times(3)).poolSetMapStatus(poolId,
                PoolHealthMonitorMappingStatus.ACTIVE)
            And ("The there is a problem with the update")
            failUpdate = true
            healthMonitorUT ! ConfigUpdate(createFakePoolConfig(NormalIp,
                goodSocketPath))
            Then ("The status should have been set to ERROR")
            eventually { confWrites should be (4) }
            eventually { setupFailures should be (1) }
            verify(mockClient, times(3)).poolSetMapStatus(poolId,
                PoolHealthMonitorMappingStatus.ACTIVE)
            verify(mockClient, times(1)).poolSetMapStatus(poolId,
                PoolHealthMonitorMappingStatus.ERROR)
            failUpdate = false
        }
    }

    feature("HaproxyHealthMonitor handles socket reads") {
        scenario ("HaproxyHealthMonitor reads the haproxy socket") {
            When ("HaproxyHealthMonitor is started")
            Then ("then socket should read.")
            eventually (timeout(Span(2, Seconds))) { socketReads should be > 0}
            verify(mockClient, times(1)).poolSetMapStatus(poolId,
                PoolHealthMonitorMappingStatus.ACTIVE)
        }
        scenario ("HaproxyHealthMonitor fails to read a socket") {
            When (" A bad socket path is sent to HaproxyHealthMonitor")
            healthMonitorUT ! ConfigUpdate(createFakePoolConfig("10.10.10.10",
                                                                badSocketPath))
            Then ("The manager should receive a failure notification")
            eventually (timeout(Span(2, Seconds)))
                { sockReadFailures should be > 0 }
            verify(mockClient, times(1)).poolSetMapStatus(poolId,
                PoolHealthMonitorMappingStatus.ERROR)
        }
    }

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
                                 client: DataClient,
                                 hostId: UUID)
        extends HaproxyHealthMonitor(config: PoolConfig,
                                     manager: ActorRef,
                                     routerId: UUID,
                                     client: DataClient,
                                     hostId: UUID) {

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
            manager ! MonitorActorUp
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
