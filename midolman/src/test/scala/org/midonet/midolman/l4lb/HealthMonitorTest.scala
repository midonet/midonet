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

import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration

import akka.actor.{Actor, ActorRef, Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex
import org.junit.runner.RunWith
import org.mockito.Mockito.{verify => mverify, reset, timeout => mtimeo}
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Seconds, Span}

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.cluster.util.SequenceDispenser.SequenceType
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.l4lb.HaproxyHealthMonitor.{SetupFailure, ConfigUpdate, RouterAdded, RouterRemoved}
import org.midonet.midolman.l4lb.HealthMonitor.{ConfigAdded, ConfigDeleted, ConfigUpdated, RouterChanged}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.MidonetEventually

@Ignore
@RunWith(classOf[JUnitRunner])
class HealthMonitorTest extends FeatureSpec
                               with Matchers
                               with GivenWhenThen
                               with BeforeAndAfter
                               with OneInstancePerTest
                               with MockitoSugar {

    /*

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
            case x =>
        }
    }

    var healthMonitorUT: ActorRef = _
    var haproxyFakeActor: ActorRef = _
    var actorSystem: ActorSystem = null
    val poolId = UUID.randomUUID()
    var mockClient = mock[LocalDataClientImpl]

    //Accounting
    var newActors = 0
    var configUpdates = 0
    var routerAdded = 0
    var routerRemoved = 0

    before {
        actorSystem = ActorSystem.create("HaproxyTestActors",
            ConfigFactory.load().getConfig("midolman"))
        healthMonitorUT = actorSystem.actorOf(Props(new HealthMonitorUT))
    }

    after {
        actorSystem.shutdown()
        reset(mockClient)
    }

    feature("HealthMonitor notifies config updates") {
        scenario ("update a config with an instance") {
            Given ("a haproxy health monitor instance")
            healthMonitorUT ! ConfigAdded(poolId, createFakePoolConfig(true),
                                          UUID.randomUUID())
            eventually (timeout(Span(3, Seconds)))
                { newActors should be (1) }
            When ("the instance has an updated config")
            healthMonitorUT ! ConfigUpdated(poolId, createFakePoolConfig(true),
                                            UUID.randomUUID())
            Then ("the instance should have recieved")
            eventually (timeout(Span(3, Seconds)))
                { configUpdates should be (1) }
        }
        scenario ("update a config with no instance") {
            When ("an update is sent about an instance that doesn't exist")
            healthMonitorUT ! ConfigUpdated(poolId, createFakePoolConfig(true),
                                            null)
            Then ("the status should be set to INACTIVE")
            mverify(mockClient, mtimeo(100).times(1)).poolSetMapStatus(poolId,
                PoolHealthMonitorMappingStatus.INACTIVE)
        }
    }
    feature ("HealthMonitor handles new configs") {
        scenario ("new config is added with no router") {
            When ("a config is added with no router")
            healthMonitorUT ! ConfigAdded(poolId, createFakePoolConfig(true),
                                            null)
            Then ("the status should be updated to INACTIVE")
            mverify(mockClient, mtimeo(100).times(1)).poolSetMapStatus(poolId,
                PoolHealthMonitorMappingStatus.INACTIVE)
        }
        scenario ("new config is added with admin state down") {
            When("a config is added with admin state down")
            healthMonitorUT ! ConfigAdded(poolId, createFakePoolConfig(false),
                                          UUID.randomUUID())
            Then ("The status should be set to INACTIVE")
            mverify(mockClient, mtimeo(100).times(1)).poolSetMapStatus(poolId,
                PoolHealthMonitorMappingStatus.INACTIVE)
        }
    }
    feature ("HealthMonitor handles changes in the router") {
        scenario ("a router is deleted") {
            Given ("a config with a router")
            healthMonitorUT ! ConfigAdded(poolId, createFakePoolConfig(true),
                                          UUID.randomUUID())
            eventually (timeout(Span(3, Seconds))) { newActors should be (1) }
            When ("the router is deleted")
            healthMonitorUT ! RouterChanged(poolId, createFakePoolConfig(true),
                                            null)
            Then ("the RouterRemoved msg should be sent")
            eventually (timeout(Span(3, Seconds)))
                { routerRemoved should be (1) }
        }
        scenario ("a router is added") {
            Given ("a config associated with a instance")
            healthMonitorUT ! ConfigAdded(poolId, createFakePoolConfig(true),
                                          UUID.randomUUID())
            eventually (timeout(Span(3, Seconds))) { newActors should be (1) }
            When ("the router is added")
            healthMonitorUT ! RouterChanged(poolId, createFakePoolConfig(true),
                                            UUID.randomUUID())
            Then ("the RouterAdded msg should be sent")
            eventually (timeout(Span(3, Seconds))) { routerAdded should be (1) }
        }
        scenario ("a router is updated on a non-existent instance") {
            When ("a router is updated")
            healthMonitorUT ! RouterChanged(poolId, createFakePoolConfig(true),
                                            null)
            Then ("The state should be set to INACTIVE")
            mverify(mockClient, mtimeo(100).times(1)).poolSetMapStatus(poolId,
                PoolHealthMonitorMappingStatus.INACTIVE)
        }
    }

    def createFakePoolConfig(adminState: Boolean) = {
        val vip = new VipConfig(true, UUID.randomUUID(), "9.9.9.9", 89, null)
        val healthMonitor = new HealthMonitorConfig(true, 5, 10, 7)
        val member1  = new PoolMemberConfig(true, UUID.randomUUID(),
                                            10, "10.11.12.13", 81)
        val member2  = new PoolMemberConfig(true, UUID.randomUUID(),
                                            10, "10.11.12.14", 81)
        val member3  = new PoolMemberConfig(true, UUID.randomUUID(),
                                            10, "10.11.12.15", 81)
        new PoolConfig(poolId, UUID.randomUUID(), Set(vip),
                Set(member1, member2, member3), healthMonitor, adminState, "",
                "_MN")
    }

    protected val backendCfg = new MidonetBackendConfig(
        ConfigFactory.parseString(""" zookeeper.root_key = '/' """))

    /*
     * This is a testable version of the HaproxyHealthMonitor. This overrides
     * the functions that would block and perform IO.
     */
     *
    class HealthMonitorUT extends HealthMonitor {
        override def preStart(): Unit = {
            client = mockClient
        }

        override val seqDispenser = new SequenceDispenser(null, backendCfg)

        override def startChildHaproxyMonitor(poolId: UUID, config: PoolConfig,
                                              routerId: UUID) = {
            haproxyFakeActor = context.actorOf(
                Props(new HaproxyFakeActor), poolId.toString)
            haproxyFakeActor
        }
    }

    */
}
