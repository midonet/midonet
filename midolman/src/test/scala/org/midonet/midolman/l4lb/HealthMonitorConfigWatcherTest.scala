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

import java.util.{Random, UUID}

import scala.collection.mutable.{HashMap => MMap}
import scala.collection.immutable.{HashMap => IMap}
import scala.concurrent.duration._

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.state.l4lb.{VipSessionPersistence, LBStatus, HealthMonitorType}
import org.midonet.midolman.l4lb.HealthMonitor.{ConfigUpdated, ConfigDeleted, ConfigAdded}
import org.midonet.midolman.l4lb.HealthMonitorConfigWatcher.BecomeHaproxyNode
import org.midonet.midolman.simulation.{Vip => SimVip, LoadBalancer => SimLoadBalancer, PoolMember => SimPoolMember, CustomMatchers}
import org.midonet.midolman.topology.devices.{HealthMonitor => SimHealthMonitor, PoolHealthMonitorMap, PoolHealthMonitor}
import org.midonet.packets.IPv4Addr


@RunWith(classOf[JUnitRunner])
class HealthMonitorConfigWatcherTest extends TestKit(ActorSystem("HealthMonitorConfigWatcherTest"))
        with FeatureSpecLike
        with CustomMatchers
        with BeforeAndAfter
        with GivenWhenThen
        with ImplicitSender
        with Matchers {

    val random = new Random()
    var watcher: ActorRef = null
    val actorSystem = ActorSystem.create("HaproxyTestActors",
            ConfigFactory.load().getConfig("midolman"))

    val uuidOne = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val uuidTwo = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val uuidThree = UUID.fromString("00000000-0000-0000-0000-000000000003")

    before {
        watcher = actorSystem.actorOf(HealthMonitorConfigWatcher.props(
            "/doesnt/matter", "/dont/care", testActor))
    }

    after {
        actorSystem.stop(watcher)
    }

    def generateFakeData(poolId: UUID): PoolHealthMonitor = {
        val hm = new SimHealthMonitor(UUID.randomUUID(),
                                      adminStateUp = true,
                                      HealthMonitorType.TCP,
                                      LBStatus.ACTIVE,
                                      delay = 1,
                                      timeout = 1,
                                      maxRetries = 1)

        val vip = new SimVip(UUID.randomUUID(),
                             adminStateUp = true,
                             poolId,
                             IPv4Addr.random,
                             protocolPort = random.nextInt(65533) + 1,
                             sessionPersistence =
                                 VipSessionPersistence.SOURCE_IP)

        val lb = new SimLoadBalancer(UUID.randomUUID(),
                                     adminStateUp = true,
                                     routerId = UUID.randomUUID(),
                                     Array(vip))

        PoolHealthMonitor(hm, lb, Array(vip), Array[SimPoolMember]())
    }

    def generateFakeMap(): MMap[UUID, PoolHealthMonitor] = {
        val map = new MMap[UUID, PoolHealthMonitor]()
        map.put(uuidOne, generateFakeData(uuidOne))
        map.put(uuidTwo, generateFakeData(uuidTwo))
        map.put(uuidThree, generateFakeData(uuidThree))
        map
    }

    feature("The config watcher only sends us updates if it is the leader") {
        scenario("config watcher is not the leader") {
            Given("A pool-health monitor mapping")
            val map = IMap(generateFakeMap().toSeq:_*)
            When("We send it to the config watcher")
            watcher ! PoolHealthMonitorMap(map)
            Then("We should expect nothing in return")
            expectNoMsg(50 milliseconds)
            And("We send more updates")
            watcher ! PoolHealthMonitorMap(map)
            Then("We should expect nothing in return")
            expectNoMsg(50 milliseconds)
            watcher ! PoolHealthMonitorMap(new IMap[UUID, PoolHealthMonitor]())
            expectNoMsg(50 milliseconds)
            watcher ! PoolHealthMonitorMap(map)
            expectNoMsg(50 milliseconds)
            map(uuidOne).healthMonitor.delay = 2
            watcher ! PoolHealthMonitorMap(map)
            expectNoMsg(50 milliseconds)
        }
        scenario("config watcher becomes the leader after several updates " +
                 "are sent") {
            Given("A pool-health monitor mapping")
            val map = generateFakeMap()
            When("We send it to the config watcher")
            watcher ! PoolHealthMonitorMap(IMap(map.toSeq:_*))
            Then("We should expect nothing in return")
            expectNoMsg(50 milliseconds)
            watcher ! PoolHealthMonitorMap(IMap(map.toSeq:_*))
            expectNoMsg(50 milliseconds)
            watcher ! PoolHealthMonitorMap(new IMap[UUID, PoolHealthMonitor]())
            expectNoMsg(50 milliseconds)
            watcher ! PoolHealthMonitorMap(IMap(map.toSeq:_*))
            expectNoMsg(50 milliseconds)
            map(uuidOne).healthMonitor.delay = 2
            watcher ! PoolHealthMonitorMap(IMap(map.toSeq:_*))
            When("We become the leader node")
            watcher ! BecomeHaproxyNode
            val res = new MMap[UUID, PoolConfig]
            Then("We should receive the correct map")
            val conf1 = expectMsgType[ConfigAdded]
            res put (conf1.poolId, conf1.config)
            val conf2 = expectMsgType[ConfigAdded]
            res put (conf2.poolId, conf2.config)
            val conf3 = expectMsgType[ConfigAdded]
            res put (conf3.poolId, conf3.config)
            res(uuidOne).healthMonitor.delay shouldEqual 2
            res(uuidTwo).healthMonitor.delay shouldEqual 1
            res(uuidThree).healthMonitor.delay shouldEqual 1
        }
    }

    feature("The config watcher updates based on changes") {
        scenario("pool config is deleted") {
            watcher ! BecomeHaproxyNode
            Given("A pool-health monitor mapping")
            val map = generateFakeMap()
            When("We send it to the config watcher")
            watcher ! PoolHealthMonitorMap(IMap(map.toSeq:_*))
            Then("We should recieve the ConfigAdded for each mapping")
            expectMsgType[ConfigAdded]
            expectMsgType[ConfigAdded]
            expectMsgType[ConfigAdded]
            map remove uuidTwo
            And("We remove one of the maps")
            watcher ! PoolHealthMonitorMap(IMap(map.toSeq:_*))
            Then("We should receive the deletion back")
            val conf = expectMsgType[ConfigDeleted]
            conf.id shouldEqual uuidTwo
        }
        scenario("pool config is updated") {
            watcher ! BecomeHaproxyNode
            Given("A pool-health monitor mapping")
            val map = generateFakeMap()
            When("We send it to the config watcher")
            watcher ! PoolHealthMonitorMap(IMap(map.toSeq:_*))
            Then("We should recieve the ConfigAdded for each mapping")
            expectMsgType[ConfigAdded]
            expectMsgType[ConfigAdded]
            expectMsgType[ConfigAdded]
            And("We update one of the maps")
            map(uuidTwo).healthMonitor.delay = 10
            watcher ! PoolHealthMonitorMap(IMap(map.toSeq:_*))
            Then("We should receive the update notification back")
            val conf = expectMsgType[ConfigUpdated]
            conf.config.healthMonitor.delay shouldEqual 10
        }
    }
}
