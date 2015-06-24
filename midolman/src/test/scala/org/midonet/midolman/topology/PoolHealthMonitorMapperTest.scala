/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.topology

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.Observable

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons.LBStatus
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.models.{Topology => Proto}
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.midolman.topology.TopologyBuilder._
import org.midonet.midolman.topology.devices.PoolHealthMonitorMap
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class PoolHealthMonitorMapperTest extends MidolmanSpec
                                          with TopologyBuilder
                                          with TopologyMatchers {

    type SimObserver = TestAwaitableObserver[PoolHealthMonitorMap]

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private final val timeout = 5 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    private def assertThread(): Unit = {
        assert(vt.vtThreadId == Thread.currentThread.getId)
    }

    feature("The mapper emits pool health monitor maps") {
        scenario("The mapper emits an empty map") {
            Given("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see an empty map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.head.mappings.isEmpty shouldBe true
        }

        scenario("The mapper emits an existing pool health monitor map") {
            Given("A health monitor")
            val hm = createHealthMonitor()
            store.create(hm)

            And("A load balancer")
            val lb = createLoadBalancer()
            store.create(lb)

            And("A pool")
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            store.create(pool)

            And("A pool member")
            val pm = createPoolMember(poolId = Some(pool.getId),
                                      status = Some(LBStatus.ACTIVE),
                                      weight = Some(1))
            store.create(pm)

            And("A vip")
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))
            store.create(vip)

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout)

            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true
            map.contains(fromProto(pool.getId)) shouldBe true

            val phm = map(fromProto(pool.getId))
            phm.healthMonitor.id shouldBe fromProto(hm.getId)
            phm.loadBalancer.id shouldBe fromProto(lb.getId)
            phm.poolMembers.map(_.id).toList shouldBe List(fromProto(pm.getId))
            phm.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The mapper emits a map on pool member update") {
            // Initial object creations
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            val pm1 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))

            store.create(hm)
            store.create(lb)
            store.create(pool)
            store.create(pm1)
            store.create(vip)

            // Create mapper and subscribe
            val mapper = new PoolHealthMonitorMapper(vt)
            val obs = new SimObserver()
            Observable.create(mapper).subscribe(obs)

            // Wait for stable state
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.last.mappings.nonEmpty shouldBe true
            obs.reset()

            // Update the pool member
            val pmUpdated = pm1.setAdminStateUp(true)
            store.update(pmUpdated)

            // wait for an update (
            obs.awaitOnNext(1, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true
            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf hm
            data.loadBalancer.id shouldBe fromProto(lb.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(pm1.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
            data.poolMembers.head.adminStateUp shouldBe true
        }

        scenario("The mapper emits a map on pool member addition") {
            // Initial object creations
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            val pm1 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))

            store.create(hm)
            store.create(lb)
            store.create(pool)
            store.create(pm1)
            store.create(vip)

            // Create mapper and subscribe
            val mapper = new PoolHealthMonitorMapper(vt)
            val obs = new SimObserver()
            Observable.create(mapper).subscribe(obs)

            // Wait for stable state
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.last.mappings.nonEmpty shouldBe true
            obs.reset()

            // Create a new pool member
            val pm2 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            store.create(pm2)

            // wait for an update
            obs.awaitOnNext(1, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true
            map.contains(pool.getId) shouldBe true

            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf hm
            data.loadBalancer.id shouldBe fromProto(lb.getId)
            data.poolMembers.map(_.id).toSet shouldBe
                Set(fromProto(pm1.getId), fromProto(pm2.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The mapper emits a map on pool member removal") {
            // Initial object creations
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            val pm1 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))

            store.create(hm)
            store.create(lb)
            store.create(pool)
            store.create(pm1)
            store.create(vip)

            // Create mapper and subscribe
            val mapper = new PoolHealthMonitorMapper(vt)
            val obs = new SimObserver()
            Observable.create(mapper).subscribe(obs)

            // Wait for stable state
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.last.mappings.nonEmpty shouldBe true
            obs.reset()

            // Remove a pool member
            store.delete(classOf[Proto.PoolMember], pm1.getId)

            // wait for an update
            obs.awaitOnNext(1, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true
            map.contains(pool.getId) shouldBe true

            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf hm
            data.loadBalancer.id shouldBe fromProto(lb.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set()
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The mapping is removed on pool delete") {
            // Initial object creations
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            val pm1 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))

            store.create(hm)
            store.create(lb)
            store.create(pool)
            store.create(pm1)
            store.create(vip)

            // Create mapper and subscribe
            val mapper = new PoolHealthMonitorMapper(vt)
            val obs = new SimObserver()
            Observable.create(mapper).subscribe(obs)

            // Wait for stable state
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.last.mappings.nonEmpty shouldBe true
            obs.reset()

            // Remove the pool member before removing the pool
            store.delete(classOf[Proto.PoolMember], pm1.getId)
            obs.awaitOnNext(1, timeout) shouldBe true

            // remove the pool
            store.delete(classOf[Proto.Pool], pool.getId)

            // wait for an update (
            obs.awaitOnNext(2, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map.isEmpty shouldBe true
        }

        scenario("The mapper emits a map on health monitor update") {
            // Initial object creations
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            val pm1 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))

            store.create(hm)
            store.create(lb)
            store.create(pool)
            store.create(pm1)
            store.create(vip)

            // Create mapper and subscribe
            val mapper = new PoolHealthMonitorMapper(vt)
            val obs = new SimObserver()
            Observable.create(mapper).subscribe(obs)

            // Wait for stable state
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.last.mappings.nonEmpty shouldBe true
            obs.reset()

            // Update the health monitor
            val hmUpdated = hm.setMaxRetries(42).setPoolId(pool.getId)
            store.update(hmUpdated)

            // wait for an update (
            obs.awaitOnNext(1, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true
            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf hmUpdated
            data.loadBalancer.id shouldBe fromProto(lb.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(pm1.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
            data.healthMonitor.maxRetries shouldBe 42
        }

        scenario("The mapper emits a map on health monitor change") {
            // Initial object creations
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            val pm1 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))

            store.create(hm)
            store.create(lb)
            store.create(pool)
            store.create(pm1)
            store.create(vip)

            // Create mapper and subscribe
            val mapper = new PoolHealthMonitorMapper(vt)
            val obs = new SimObserver()
            Observable.create(mapper).subscribe(obs)

            // Wait for stable state
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.last.mappings.nonEmpty shouldBe true
            obs.reset()

            // Get the pool from zoom (to account for binding-generated changes)
            val savedPool =
                Await.result(store.get(classOf[Proto.Pool], pool.getId), timeout)
            // Change the health monitor
            val hm2 = createHealthMonitor()
            store.create(hm2)
            val poolUpdated = savedPool.setHealthMonitorId(hm2.getId)
            store.update(poolUpdated)

            // wait for an update (
            obs.awaitOnNext(3, timeout) shouldBe true
            // Depending on the timing, we may see an empty map
            // while waiting for the new health monitor data
            if (obs.getOnNextEvents.last.mappings.isEmpty)
                obs.awaitOnNext(4, timeout) shouldBe true

            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true

            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf hm2
            data.loadBalancer.id shouldBe fromProto(lb.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(pm1.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The map is cleared if no health monitor is associated") {
            // Initial object creations
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            val pm1 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))

            store.create(hm)
            store.create(lb)
            store.create(pool)
            store.create(pm1)
            store.create(vip)

            // Create mapper and subscribe
            val mapper = new PoolHealthMonitorMapper(vt)
            val obs = new SimObserver()
            Observable.create(mapper).subscribe(obs)

            // Wait for stable state
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.last.mappings.nonEmpty shouldBe true
            obs.reset()

            // Get the pool from zoom (to account for binding-generated changes)
            val savedPool =
                Await.result(store.get(classOf[Proto.Pool], pool.getId), timeout)
            // De-associate the health monitor
            val poolUpdated = savedPool.removeHealthMonitorId()
            store.update(poolUpdated)

            // wait for an update (
            obs.awaitOnNext(2, timeout) shouldBe true

            val map = obs.getOnNextEvents.last.mappings
            map.isEmpty shouldBe true
        }

        scenario("The mapper emits a map on load balancer update") {
            // Initial object creations
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            val pm1 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))

            store.create(hm)
            store.create(lb)
            store.create(pool)
            store.create(pm1)
            store.create(vip)

            // Create mapper and subscribe
            val mapper = new PoolHealthMonitorMapper(vt)
            val obs = new SimObserver()
            Observable.create(mapper).subscribe(obs)

            // Wait for stable state
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.last.mappings.nonEmpty shouldBe true
            obs.reset()

            // Get the data from zoom (to account for binding-generated changes)
            val savedLb =
                Await.result(store.get(classOf[Proto.LoadBalancer], lb.getId), timeout)
            // Update the load balancer
            val lbUpdated = savedLb.setAdminStateUp(true)
            store.update(lbUpdated)

            // wait for an update (
            obs.awaitOnNext(1, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true
            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf hm
            data.loadBalancer.id shouldBe fromProto(lb.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(pm1.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
            data.loadBalancer.adminStateUp shouldBe true
        }

        scenario("The mapper emits a map on load balancer change") {
            // Initial object creations
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            val pm1 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))

            store.create(hm)
            store.create(lb)
            store.create(pool)
            store.create(pm1)
            store.create(vip)

            // Create mapper and subscribe
            val mapper = new PoolHealthMonitorMapper(vt)
            val obs = new SimObserver()
            Observable.create(mapper).subscribe(obs)

            // Wait for stable state
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.last.mappings.nonEmpty shouldBe true
            obs.reset()

            // Get the pool from zoom (to account for binding-generated changes)
            val savedPool =
                Await.result(store.get(classOf[Proto.Pool], pool.getId), timeout)
            // Change the health monitor
            val lb2 = createLoadBalancer()
            val vip2 = createVip(loadBalancerId = Some(lb2.getId),
                                 poolId = Some(pool.getId))
            val poolUpdated = savedPool.setLoadBalancerId(lb2.getId)
            store.create(lb2)
            store.create(vip2)
            store.update(poolUpdated)

            // wait for an update (
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on the timing, we may see an empty map
            // while waiting for the new health monitor data
            if (obs.getOnNextEvents.last.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true

            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true

            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf hm
            data.loadBalancer.id shouldBe fromProto(lb2.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(pm1.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip2.getId))
        }

        scenario("The map is cleared if no load balancer is associated") {
            // Initial object creations
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            val pm1 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))

            store.create(hm)
            store.create(lb)
            store.create(pool)
            store.create(pm1)
            store.create(vip)

            // Create mapper and subscribe
            val mapper = new PoolHealthMonitorMapper(vt)
            val obs = new SimObserver()
            Observable.create(mapper).subscribe(obs)

            // Wait for stable state
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.last.mappings.nonEmpty shouldBe true
            obs.reset()

            // Get the pool from zoom (to account for binding-generated changes)
            val savedPool =
                Await.result(store.get(classOf[Proto.Pool], pool.getId), timeout)
            // De-associate the load balancer
            val poolUpdated = savedPool.removeLoadBalancerId()
            store.update(poolUpdated)

            // wait for an update (
            obs.awaitOnNext(1, timeout) shouldBe true

            val map = obs.getOnNextEvents.last.mappings
            map.isEmpty shouldBe true
        }

        scenario("The mapper emits a map on vip update") {
            // Initial object creations
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            val pm1 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))

            store.create(hm)
            store.create(lb)
            store.create(pool)
            store.create(pm1)
            store.create(vip)

            // Create mapper and subscribe
            val mapper = new PoolHealthMonitorMapper(vt)
            val obs = new SimObserver()
            Observable.create(mapper).subscribe(obs)

            // Wait for stable state
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.last.mappings.nonEmpty shouldBe true
            obs.reset()

            // Update the vip
            val vipUpdated = vip.setAddress(IPAddressUtil.toProto("10.0.0.1"))
            store.update(vipUpdated)

            // wait for an update (
            obs.awaitOnNext(1, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true
            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf hm
            data.loadBalancer.id shouldBe fromProto(lb.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(pm1.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
            data.vips.head.address shouldBe IPv4Addr.fromString("10.0.0.1")
        }

        scenario("The mapper emits a map on vip addition") {
            // Initial object creations
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            val pm1 = createPoolMember(poolId = Some(pool.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId),
                                loadBalancerId = Some(lb.getId))

            store.create(hm)
            store.create(lb)
            store.create(pool)
            store.create(pm1)
            store.create(vip)

            // Create mapper and subscribe
            val mapper = new PoolHealthMonitorMapper(vt)
            val obs = new SimObserver()
            Observable.create(mapper).subscribe(obs)

            // Wait for stable state
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on timing we may see the initial empty map
            if (obs.getOnNextEvents.head.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.last.mappings.nonEmpty shouldBe true
            obs.reset()

            // Add vips to load balancer (only one for existing pool)
            val vip2 = createVip(loadBalancerId = Some(lb.getId))
            val vip3 = createVip(loadBalancerId = Some(lb.getId),
                                 poolId = Some(pool.getId))
            store.create(vip2)
            store.create(vip3)

            // wait for 2 updates (vip2 is not part of the pool health monitor,
            // but it changes the load balancer information, so we may receive
            // an update for it
            obs.awaitOnNext(1, timeout) shouldBe true
            if (obs.getOnNextEvents.last.mappings.values.head.vips.size < 2)
                obs.awaitOnNext(2, timeout) shouldBe true

            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true
            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf hm
            data.loadBalancer.id shouldBe fromProto(lb.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(pm1.getId))
            data.vips.map(_.id).toSet shouldBe
                Set(fromProto(vip.getId), fromProto(vip3.getId))
        }
    }
}
