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
package org.midonet.midolman

import java.util.UUID
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.ActorSystem
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.PoolHealthMonitorMapRequest
import org.midonet.midolman.topology.devices.{PoolHealthMonitorMap, HealthMonitor => SimHealthMonitor}
import org.midonet.midolman.simulation.{VIP => SimVIP}
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class PoolHealthMonitorMapManagerTest
        extends TestKit(ActorSystem("PoolHealthMonitorMapManagerTest"))
        with MidolmanSpec
        with ImplicitSender {

    var vta: TestableVTA = null

    registerActors(VirtualTopologyActor -> (() => new TestableVTA))

    protected override def beforeTest() {
        VirtualTopologyActor.clearTopology()
        vta = VirtualTopologyActor.as[TestableVTA]
    }

    private def emulatePoolHealthMonitorMapping(
            pool: UUID, status: PoolHealthMonitorMappingStatus): Unit =
        setPoolMapStatus(pool, status)

    private def emulatePoolHealthMonitorMappingActivate(pool: UUID): Unit =
        emulatePoolHealthMonitorMapping(pool,
            PoolHealthMonitorMappingStatus.ACTIVE)

    private def emulatePoolHealthMonitorMappingDeactivate(pool: UUID): Unit =
        emulatePoolHealthMonitorMapping(pool,
            PoolHealthMonitorMappingStatus.INACTIVE)

    def healthMonitorShouldEqual(mapHm: SimHealthMonitor,
                                 hm: UUID) = {
        matchHealthMonitor(hm, mapHm.adminStateUp, mapHm.delay, mapHm.timeout,
                           mapHm.maxRetries) shouldBe true
    }

    def vipShouldEqual(mapVip: SimVIP, vip: UUID) = {
        matchVip(vip, mapVip.address, mapVip.protocolPort)
    }

    if (!awaitingImpl) {
    feature("PoolHealthMonitorMapManager handles associations") {
        scenario("PoolHealthMonitorMap with two associations") {
            Given("two pools using the same health monitor")
            val loadBalancer = newLoadBalancer()
            val loadBalancer2 = newLoadBalancer()
            val hm = newHealthMonitor()
            val pool = newPool(loadBalancer, hmId = hm)
            val pool2 = newPool(loadBalancer2, hmId = hm)
            emulatePoolHealthMonitorMappingActivate(pool)
            emulatePoolHealthMonitorMappingActivate(pool2)
            val vip = newRandomVip(pool)
            val vip2 = newRandomVip(pool2)

            When("the VTA receives a request for the map")
            vta.self ! PoolHealthMonitorMapRequest(update = true)

            Then("it should return the map")
            // skip initial empty map
            val initial = expectMsgType[PoolHealthMonitorMap]
            val map = if (initial.mappings.nonEmpty) initial
                else expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map.mappings(pool).healthMonitor, hm)
            vipShouldEqual(map.mappings(pool).vips.head, vip)
            healthMonitorShouldEqual(
                map.mappings(pool2).healthMonitor, hm)
            vipShouldEqual(map.mappings(pool2).vips.head, vip2)
        }

        scenario("Receive update when an association is added") {
            Given("a pool with a health monitor")
            val loadBalancer = newLoadBalancer()
            val hm = newRandomHealthMonitor()
            val pool = newPool(loadBalancer, hmId = hm)
            emulatePoolHealthMonitorMappingActivate(pool)
            val vip = newRandomVip(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! PoolHealthMonitorMapRequest(update = true)

            And("it returns the first version of the map")
            val initial = expectMsgType[PoolHealthMonitorMap]
            val map = if (initial.mappings.nonEmpty) initial
                else expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map.mappings(pool).healthMonitor, hm)
            vipShouldEqual(map.mappings(pool).vips.head, vip)

            And("a new association is added")
            val loadBalancer2 = newLoadBalancer()
            val pool2 = newPool(loadBalancer2, hmId = hm)
            emulatePoolHealthMonitorMappingActivate(pool2)
            expectMsgType[PoolHealthMonitorMap]
            val vip2 = newRandomVip(pool2)

            Then("the VTA should send an update")
            val newMap
                    = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                newMap.mappings(pool2).healthMonitor, hm)
            vipShouldEqual(newMap.mappings(pool2).vips.head, vip2)
        }

        scenario("Receive update when an association is removed") {
            Given("a pool with a health monitor")
            val loadBalancer = newLoadBalancer()
            val loadBalancer2 = newLoadBalancer()
            val hm = newRandomHealthMonitor()
            val hm2 = newRandomHealthMonitor()
            val pool = newPool(loadBalancer, hmId = hm)
            val pool2 = newPool(loadBalancer2, hmId = hm2)
            emulatePoolHealthMonitorMappingActivate(pool)
            emulatePoolHealthMonitorMappingActivate(pool2)
            val vip = newRandomVip(pool)
            val vip2 = newRandomVip(pool2)

            When("the VTA receives a subscription request for it")
            vta.self ! PoolHealthMonitorMapRequest(update = true)

            And("it returns the first version of the mapping")
            val initial = expectMsgType[PoolHealthMonitorMap]
            val map = if (initial.mappings.nonEmpty) initial
                else expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map.mappings(pool).healthMonitor, hm)
            vipShouldEqual(map.mappings(pool).vips.head, vip)
            healthMonitorShouldEqual(
                map.mappings(pool2).healthMonitor, hm2)
            vipShouldEqual(map.mappings(pool2).vips.head, vip2)

            And("an existing health monitor is removed")
            emulatePoolHealthMonitorMappingActivate(pool)
            emulatePoolHealthMonitorMappingActivate(pool2)
            deleteHealthMonitor(hm)
            emulatePoolHealthMonitorMappingDeactivate(pool)
            emulatePoolHealthMonitorMappingDeactivate(pool2)

            Then("the VTA should send an update")
            val map2 = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map2.mappings(pool2).healthMonitor, hm2)
            vipShouldEqual(map2.mappings(pool2).vips.head, vip2)
            map2.mappings.size shouldBe 1
        }

        scenario("Receive update when an association config is changed") {
            Given("a pool and a health monitor")
            val loadBalancer = newLoadBalancer()
            val hm = newRandomHealthMonitor()
            val pool = newPool(loadBalancer, hmId = hm)
            emulatePoolHealthMonitorMappingActivate(pool)
            val vip = newRandomVip(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! PoolHealthMonitorMapRequest(update = true)

            And("it returns the first version of the mapping")
            val initial = expectMsgType[PoolHealthMonitorMap]
            val map = if (initial.mappings.nonEmpty) initial
                else expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map.mappings(pool).healthMonitor, hm)
            vipShouldEqual(map.mappings(pool).vips.head, vip)

            And("the health monitor is changed")
            emulatePoolHealthMonitorMappingActivate(pool)
            val hmDevice = map.mappings(pool).healthMonitor
            setHealthMonitorDelay(hm, hmDevice.delay + 1)

            Then("the VTA should send an update")
            val map2 = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map2.mappings(pool).healthMonitor, hm)
            vipShouldEqual(map2.mappings(pool).vips.head, vip)
            map2.mappings.size shouldBe 1
        }
        scenario("Receive update when a pool changes its health monitor") {
            Given("a pool and a health monitor")
            val loadBalancer = newLoadBalancer()
            val hm = newRandomHealthMonitor()
            val hm2 = newRandomHealthMonitor()
            val pool = newPool(loadBalancer, hmId = hm)
            emulatePoolHealthMonitorMappingActivate(pool)
            val vip = newRandomVip(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! PoolHealthMonitorMapRequest(update = true)
            emulatePoolHealthMonitorMappingActivate(pool)

            And("it returns the first version of the mapping")
            val initial = expectMsgType[PoolHealthMonitorMap]
            val map = if (initial.mappings.nonEmpty) initial
                else expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map.mappings(pool).healthMonitor, hm)
            vipShouldEqual(map.mappings(pool).vips.head, vip)

            And("the health monitor is disassociated with the pool")
            setPoolHealthMonitor(pool, null)
            emulatePoolHealthMonitorMappingDeactivate(pool)

            Then("the VIA should send an update")
            val map2 = expectMsgType[PoolHealthMonitorMap]
            map2.mappings.size shouldBe 0

            And("the health monitor of the pool is changed")
            setPoolHealthMonitor(pool, hm2)
            emulatePoolHealthMonitorMappingActivate(pool)

            Then("the VTA should send an update")
            val map3 = expectMsgType[PoolHealthMonitorMap]
            healthMonitorShouldEqual(
                map3.mappings(pool).healthMonitor, hm2)
            vipShouldEqual(map3.mappings(pool).vips.head, vip)
            map3.mappings.size shouldBe 1
        }
    }
    }
}
