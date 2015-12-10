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

import scala.collection.immutable.{HashMap => IMap}
import scala.collection.mutable.{HashMap => MMap}
import scala.concurrent.duration._

import akka.actor.{Props, ActorRef, ActorSystem}
import akka.testkit.TestKit

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.l4lb.HealthMonitor.{ConfigAdded, ConfigDeleted, ConfigUpdated}
import org.midonet.midolman.l4lb.HealthMonitorConfigWatcher.BecomeHaproxyNode
import org.midonet.midolman.simulation.{LoadBalancer => SimLoadBalancer, PoolMember => SimPoolMember, Vip => SimVip}
import org.midonet.midolman.state.l4lb.{HealthMonitorType, LBStatus, VipSessionPersistence}
import org.midonet.midolman.topology.devices.{HealthMonitor => SimHealthMonitor, PoolHealthMonitor, PoolHealthMonitorMap}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class HealthMonitorConfigWatcherTest
    extends TestKit(ActorSystem("HealthMonitorConfigWatcherTest"))
    with MidolmanSpec {

    val random = new Random()
    var watcher: ActorRef = null

    val uuidOne = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val uuidTwo = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val uuidThree = UUID.fromString("00000000-0000-0000-0000-000000000003")

    class TestableHealthMonitorConfigWatcher
        extends HealthMonitorConfigWatcher("/doesnt/matter", "/dont/care", testActor) {
        override def preStart(): Unit = {
            // Override preStart to not subscribe to the VT for pool health
            // monitor mappings, which will introduce additional notifications.
        }
    }

    protected override def beforeTest(): Unit = {
        watcher = actorSystem.actorOf(Props(new TestableHealthMonitorConfigWatcher()))
    }

    protected override def afterTest(): Unit = {
        actorSystem.stop(watcher)
    }

    def generateFakeData(poolId: UUID, stateUp: Boolean = true)
    : PoolHealthMonitor = {
        val hm = new SimHealthMonitor(UUID.randomUUID(),
                                      adminStateUp = stateUp,
                                      HealthMonitorType.TCP,
                                      LBStatus.ACTIVE,
                                      delay = 1,
                                      timeout = 1,
                                      maxRetries = 1)

        val vip = new SimVip(UUID.randomUUID(),
                             adminStateUp = stateUp,
                             poolId,
                             IPv4Addr.random,
                             protocolPort = random.nextInt(65533) + 1,
                             sessionPersistence =
                                 VipSessionPersistence.SOURCE_IP)

        val lb = new SimLoadBalancer(UUID.randomUUID(),
                                     adminStateUp = stateUp,
                                     routerId = UUID.randomUUID(),
                                     Array(vip))

        val lbStatus = if (stateUp) LBStatus.ACTIVE else LBStatus.INACTIVE
        val ip = if (stateUp) IPv4Addr.random else null
        val port = if (stateUp) 42 else -1
        val poolMember = new SimPoolMember(id = UUID.randomUUID(),
                                           adminStateUp = stateUp,
                                           lbStatus, ip, port, weight = 0)
        PoolHealthMonitor(hm, lb, Array(vip), Array[SimPoolMember](poolMember))
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
            Then("We should receive the ConfigAdded for each mapping")
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
            Then("We should receive the ConfigAdded for each mapping")
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

    feature("Converting PoolHealthMonitor data to PoolConfig") {
        scenario("PoolConfig/PoolMemberConfig") {
            When("We generate a pool config")
            val poolId = UUID.randomUUID()
            val fileLoc = "/toto"
            val suffix = "/tata"
            val phm = generateFakeData(poolId)
            val poolConfig =
                HealthMonitorConfigWatcher.convertDataToPoolConfig(poolId,
                    fileLoc, suffix, phm)

            Then("The pool should be configurable")
            poolConfig.isConfigurable shouldBe true
            poolConfig.members.forall(_.isConfigurable) shouldBe true

            And("The conversion should be deterministic")
            val poolConfig2 =
                HealthMonitorConfigWatcher.convertDataToPoolConfig(poolId,
                    fileLoc, suffix, phm)
            poolConfig shouldBe poolConfig2

            And("The configuration string should be correct")
            val vip = poolConfig.vip
            val healthMonitor = phm.healthMonitor
            val config = new StringBuilder()
            config.append(
            s"""global
                    daemon
                    user nobody
                    group daemon
                    log /dev/log local0
                    log /dev/log local1 notice
                    stats socket ${poolConfig.haproxySockFileLoc} mode 0666
                        level user
            defaults
                    log global
                    retries 3
                    timeout connect 5000
                    timeout client 5000
                    timeout server 5000
            frontend ${vip.id.toString}
                    option tcplog
                    bind *:${vip.port}
                    mode tcp
                    default_backend $poolId
            backend $poolId
                    timeout check ${phm.healthMonitor.timeout}s
            """)
            val members = poolConfig.members
            members.foreach(x => config.append(s"server ${x.id.toString} " +
                s"${x.address}:${x.port} check inter ${healthMonitor.delay}s " +
                s"fall ${healthMonitor.maxRetries}\n"))

            poolConfig.generateConfigFile().replaceAll("\\s+","") shouldBe
                config.toString().replaceAll("\\s+","")

            When("We generate a configuration with disabled devices")
            val phm2 = generateFakeData(poolId, stateUp = false)
            val poolConfig3 =
                HealthMonitorConfigWatcher.convertDataToPoolConfig(poolId,
                    fileLoc, suffix, phm2)

            Then("The pool should not be configurable")
            poolConfig3.isConfigurable shouldBe false
            poolConfig3.members.forall(_.isConfigurable) shouldBe false

            And("The 3rd configuration should be different from the 2nd one")
            poolConfig3 shouldNot be(poolConfig2)
        }
    }
}
