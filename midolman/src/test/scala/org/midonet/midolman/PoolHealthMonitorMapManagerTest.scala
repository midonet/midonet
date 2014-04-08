/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.midonet.midolman.simulation.CustomMatchers
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.PoolHealthMonitorMapRequest
import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.ActorSystem
import org.midonet.cluster.data.l4lb.{HealthMonitor, VIP}
import org.midonet.midolman.l4lb.PoolHealthMonitorMapManager.PoolHealthMonitorMap
import org.midonet.midolman.state.zkManagers.PoolZkManager.PoolHealthMonitorMappingConfig.{VipConfigWithId, HealthMonitorConfigWithId}

// @RunWith(classOf[JUnitRunner])
class PoolHealthMonitorMapManagerTest
    extends TestKit(ActorSystem("PoolHealthMonitorMapManagerTest"))
    with FeatureSpecLike
    with CustomMatchers
    with GivenWhenThen
    with ImplicitSender
    with Matchers
    with MidolmanServices
    with MockMidolmanActors
    with OneInstancePerTest
    with VirtualConfigurationBuilders {

    var vta: TestableVTA = null

    protected override def registerActors =
        List(VirtualTopologyActor -> (() => new TestableVTA))

    protected override def beforeTest() {
        VirtualTopologyActor.clearTopology()
        vta = VirtualTopologyActor.as[TestableVTA]
    }

    def healthMonitorShouldEqual(mapHm: HealthMonitorConfigWithId,
                                 hm: HealthMonitor) = {
        mapHm.config.delay shouldEqual hm.getDelay
        mapHm.config.adminStateUp shouldEqual hm.isAdminStateUp
        mapHm.config.timeout shouldEqual hm.getTimeout
        mapHm.config.maxRetries shouldEqual hm.getMaxRetries
    }

    def vipShouldEqual(mapVip: VipConfigWithId, vip: VIP) = {
        mapVip.config.address shouldEqual vip.getAddress
        mapVip.config.protocolPort shouldEqual vip.getProtocolPort
    }

    feature("PoolHealthMonitorMapManager handles associations") {
        scenario("PoolHealthMonitorMap with two associations") {
            Given("two pools using the same health monitor")
            val loadBalancer = createLoadBalancer()
            val loadBalancer2 = createLoadBalancer()
            val hm = createHealthMonitor()
            val pool = createPool(loadBalancer, hmId = hm.getId)
            val pool2 = createPool(loadBalancer2, hmId = hm.getId)
            val vip = createRandomVip(pool)
            val vip2 = createRandomVip(pool2)

            When("the VTA receives a request for the map")
            vta.self ! PoolHealthMonitorMapRequest(false)

            Then("it should return the map")
            val map = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map.mappings(pool.getId).healthMonitorConfig, hm)
            vipShouldEqual(map.mappings(pool.getId).vipConfigs.get(0), vip)
            healthMonitorShouldEqual(
                map.mappings(pool2.getId).healthMonitorConfig, hm)
            vipShouldEqual(map.mappings(pool2.getId).vipConfigs.get(0), vip2)
        }

        scenario("Receive update when an association is added") {
            Given("a pool with a health monitor")
            val loadBalancer = createLoadBalancer()
            val hm = createRandomHealthMonitor()
            val pool = createPool(loadBalancer, hmId = hm.getId)
            val vip = createRandomVip(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! PoolHealthMonitorMapRequest(true)

            And("it returns the first version of the map")
            val map = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map.mappings(pool.getId).healthMonitorConfig, hm)
            vipShouldEqual(map.mappings(pool.getId).vipConfigs.get(0), vip)

            And("a new association is added")
            val loadBalancer2 = createLoadBalancer()
            val pool2 = createPool(loadBalancer2, hmId = hm.getId)
            expectMsgType[PoolHealthMonitorMap]
            val vip2 = createRandomVip(pool2)

            Then("the VTA should send an update")
            val newMap
                    = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                newMap.mappings(pool2.getId).healthMonitorConfig,hm)
            vipShouldEqual(newMap.mappings(pool2.getId).vipConfigs.get(0),
                           vip2)
        }

        scenario("Receive update when an association is removed") {
            Given("a pool with a health monitor")
            val loadBalancer = createLoadBalancer()
            val loadBalancer2 = createLoadBalancer()
            val hm = createRandomHealthMonitor()
            val hm2 = createRandomHealthMonitor()
            val pool = createPool(loadBalancer, hmId = hm.getId)
            val pool2 = createPool(loadBalancer2, hmId = hm2.getId)
            val vip = createRandomVip(pool)
            val vip2 = createRandomVip(pool2)

            When("the VTA receives a subscription request for it")
            vta.self ! PoolHealthMonitorMapRequest(true)

            And("it returns the first version of the mapping")
            val map = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map.mappings(pool.getId).healthMonitorConfig, hm)
            vipShouldEqual(map.mappings(pool.getId).vipConfigs.get(0), vip)
            healthMonitorShouldEqual(
                map.mappings(pool2.getId).healthMonitorConfig, hm2)
            vipShouldEqual(map.mappings(pool2.getId).vipConfigs.get(0), vip2)

            And("an existing health monitor is removed")
            deleteHealthMonitor(hm)

            Then("the VTA should send an update")
            val map2 = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map2.mappings(pool2.getId).healthMonitorConfig, hm2)
            vipShouldEqual(map2.mappings(pool2.getId).vipConfigs.get(0), vip2)
            map2.mappings.size shouldBe 1
        }

        scenario("Receive update when an association config is changed") {
            Given("a pool and a health monitor")
            val loadBalancer = createLoadBalancer()
            val hm = createRandomHealthMonitor()
            val pool = createPool(loadBalancer, hmId = hm.getId)
            val vip = createRandomVip(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! PoolHealthMonitorMapRequest(true)

            And("it returns the first version of the mapping")
            val map = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map.mappings(pool.getId).healthMonitorConfig, hm)
            vipShouldEqual(map.mappings(pool.getId).vipConfigs.get(0), vip)

            And("the health monitor is changed")
            setHealthMonitorDelay(hm, 17)

            Then("the VTA should send an update")
            val map2 = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map2.mappings(pool.getId).healthMonitorConfig, hm)
            vipShouldEqual(map2.mappings(pool.getId).vipConfigs.get(0), vip)
            map2.mappings.size shouldBe 1
        }
        scenario("Receive update when a pool changes its health monitor") {
            Given("a pool and a health monitor")
            val loadBalancer = createLoadBalancer()
            val hm = createRandomHealthMonitor()
            val hm2 = createRandomHealthMonitor()
            val pool = createPool(loadBalancer, hmId = hm.getId)
            val vip = createRandomVip(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! PoolHealthMonitorMapRequest(true)

            And("it returns the first version of the mapping")
            val map = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
            map.mappings(pool.getId).healthMonitorConfig, hm)
            vipShouldEqual(map.mappings(pool.getId).vipConfigs.get(0), vip)

            And("the health monitor of the pool is changed")
            setPoolHealthMonitor(pool, hm2.getId)

            Then("the VTA should send an update")
            val map2 = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map2.mappings(pool.getId).healthMonitorConfig, hm2)
            vipShouldEqual(map2.mappings(pool.getId).vipConfigs.get(0), vip)
            map2.mappings.size shouldBe 1
        }
    }
}
