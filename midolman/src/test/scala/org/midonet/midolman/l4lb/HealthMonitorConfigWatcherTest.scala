/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.l4lb

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import java.util.UUID
import org.junit.runner.RunWith
import org.midonet.midolman.l4lb.HealthMonitor.{ConfigUpdated, ConfigDeleted, ConfigAdded}
import org.midonet.midolman.l4lb.HealthMonitorConfigWatcher.BecomeHaproxyNode
import org.midonet.midolman.l4lb.PoolHealthMonitorMapManager.PoolHealthMonitorMap
import org.midonet.midolman.simulation.CustomMatchers
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager.LoadBalancerConfig
import org.midonet.midolman.state.zkManagers.PoolZkManager.PoolHealthMonitorMappingConfig
import org.midonet.midolman.state.zkManagers.PoolZkManager.PoolHealthMonitorMappingConfig.{PoolMemberConfigWithId,
                                                                                           VipConfigWithId,
                                                                                           HealthMonitorConfigWithId,
                                                                                           LoadBalancerConfigWithId}
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager
import org.midonet.midolman.state.zkManagers.VipZkManager
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import scala.collection.mutable.HashMap
import scala.concurrent.duration._


@RunWith(classOf[JUnitRunner])
class HealthMonitorConfigWatcherTest extends TestKit(ActorSystem("HealthMonitorConfigWatcherTest"))
        with FeatureSpecLike
        with CustomMatchers
        with BeforeAndAfter
        with GivenWhenThen
        with ImplicitSender
        with Matchers {

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

    def generateFakeData: PoolHealthMonitorMappingConfig = {
        val data = new PoolHealthMonitorMappingConfig()
        data.loadBalancerConfig = new LoadBalancerConfigWithId()
        data.loadBalancerConfig.persistedId = UUID.randomUUID()
        data.loadBalancerConfig.config = new LoadBalancerConfig()
        data.loadBalancerConfig.config.adminStateUp = true
        data.loadBalancerConfig.config.routerId = UUID.randomUUID()
        data.healthMonitorConfig = new HealthMonitorConfigWithId()
        data.healthMonitorConfig.config
            = new HealthMonitorZkManager.HealthMonitorConfig()
        data.healthMonitorConfig.config.adminStateUp = true
        data.healthMonitorConfig.config.delay = 1
        data.healthMonitorConfig.config.timeout = 1
        data.healthMonitorConfig.config.maxRetries = 1
        data.vipConfigs = new java.util.ArrayList[VipConfigWithId]()
        val vip = new VipConfigWithId()
        vip.persistedId = UUID.randomUUID()
        vip.config = new VipZkManager.VipConfig()
        vip.config.address = "10.0.0.1"
        vip.config.protocolPort = 80
        data.vipConfigs.add(vip)
        data.poolMemberConfigs
            = new java.util.ArrayList[PoolMemberConfigWithId]()
        data
    }

    def generateFakeMap(): HashMap[UUID, PoolHealthMonitorMappingConfig] = {
        val map = new HashMap[UUID, PoolHealthMonitorMappingConfig]()
        map.put(uuidOne, generateFakeData)
        map.put(uuidTwo, generateFakeData)
        map.put(uuidThree, generateFakeData)
        map
    }

    feature("The config watcher only sends us updates if it is the leader") {
        scenario("config watcher is not the leader") {
            Given("A pool-health monitor mapping")
            val map = generateFakeMap
            When("We send it to the config watcher")
            watcher ! PoolHealthMonitorMap(map)
            Then("We should expect nothing in return")
            expectNoMsg(50 milliseconds)
            And("We send more updates")
            watcher ! PoolHealthMonitorMap(map)
            Then("We should expect nothing in return")
            expectNoMsg(50 milliseconds)
            watcher ! PoolHealthMonitorMap(
                          new HashMap[UUID, PoolHealthMonitorMappingConfig]())
            expectNoMsg(50 milliseconds)
            watcher ! PoolHealthMonitorMap(map)
            expectNoMsg(50 milliseconds)
            map(uuidOne).healthMonitorConfig.config.delay = 2
            watcher ! PoolHealthMonitorMap(map)
            expectNoMsg(50 milliseconds)
        }
        scenario("config watcher becomes the leader after several updates " +
                 "are sent") {
            Given("A pool-health monitor mapping")
            val map = generateFakeMap
            When("We send it to the config watcher")
            watcher ! PoolHealthMonitorMap(map)
            Then("We should expect nothing in return")
            expectNoMsg(50 milliseconds)
            watcher ! PoolHealthMonitorMap(map)
            expectNoMsg(50 milliseconds)
            watcher ! PoolHealthMonitorMap(
                new HashMap[UUID, PoolHealthMonitorMappingConfig]())
            expectNoMsg(50 milliseconds)
            watcher ! PoolHealthMonitorMap(map)
            expectNoMsg(50 milliseconds)
            map(uuidOne).healthMonitorConfig.config.delay = 2
            watcher ! PoolHealthMonitorMap(map)
            When("We become the leader node")
            watcher ! BecomeHaproxyNode
            val res = new HashMap[UUID, PoolConfig]
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
        scenario("non-configurable LB configs are added") {
            watcher ! BecomeHaproxyNode
            Given("A pool-health monitor mapping that is not configurable")
            val map = generateFakeMap()
            map(uuidOne).vipConfigs.get(0).config.address = null
            map(uuidTwo).vipConfigs.get(0).config.address = null
            map(uuidThree).vipConfigs.get(0).config.address = null
            When("We send it to the config watcher")
            watcher ! PoolHealthMonitorMap(map)
            Then("We should expect nothing in return")
            expectNoMsg(50 milliseconds)
            When("We update one of the configs to be configurable")
            map(uuidTwo).healthMonitorConfig.config.maxRetries = 5
            map(uuidTwo).vipConfigs.get(0).config.address = "10.0.0.1"
            watcher ! PoolHealthMonitorMap(map)
            Then("We should receive only that config back")
            val conf = expectMsgType[ConfigAdded]
            conf.config.healthMonitor.maxRetries shouldEqual 5
            conf.config.vip.ip shouldEqual "10.0.0.1"
        }
        scenario("pool config is deleted") {
            watcher ! BecomeHaproxyNode
            Given("A pool-health monitor mapping")
            val map = generateFakeMap()
            When("We send it to the config watcher")
            watcher ! PoolHealthMonitorMap(map)
            Then("We should recieve the ConfigAdded for each mapping")
            expectMsgType[ConfigAdded]
            expectMsgType[ConfigAdded]
            expectMsgType[ConfigAdded]
            map remove uuidTwo
            And("We remove one of the maps")
            watcher ! PoolHealthMonitorMap(map)
            Then("We should receive the deletion back")
            val conf = expectMsgType[ConfigDeleted]
            conf.id shouldEqual uuidTwo
        }
        scenario("pool config is updated") {
            watcher ! BecomeHaproxyNode
            Given("A pool-health monitor mapping")
            val map = generateFakeMap()
            When("We send it to the config watcher")
            watcher ! PoolHealthMonitorMap(map)
            Then("We should recieve the ConfigAdded for each mapping")
            expectMsgType[ConfigAdded]
            expectMsgType[ConfigAdded]
            expectMsgType[ConfigAdded]
            And("We update one of the maps")
            map(uuidTwo).healthMonitorConfig.config.delay = 10
            watcher ! PoolHealthMonitorMap(map)
            Then("We should receive the update notification back")
            val conf = expectMsgType[ConfigUpdated]
            conf.config.healthMonitor.delay shouldEqual 10
        }
    }
}