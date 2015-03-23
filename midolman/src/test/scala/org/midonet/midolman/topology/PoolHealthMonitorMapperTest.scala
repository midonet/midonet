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

import java.util.UUID

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.Observable

import org.midonet.cluster.data.storage.{TestAwaitableObserver, Storage}
import org.midonet.cluster.models.Commons.LBStatus
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.models.{Topology => Proto, Commons}
import org.midonet.cluster.util.UUIDUtil.{fromProto, toProto}
import org.midonet.midolman.topology.devices.PoolHealthMonitorMap
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class PoolHealthMonitorMapperTest extends MidolmanSpec
                                          with TopologyBuilder
                                          with TopologyMatchers {

    type SimObserver = TestAwaitableObserver[PoolHealthMonitorMap]

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private final val timeout = 5 seconds
    private final val retries = 10

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).ownershipStore
    }

    private def assertThread(): Unit = {
        assert(vt.threadId == Thread.currentThread.getId)
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
            val hm = createTopologyObject(classOf[Proto.HealthMonitor])
            store.create(hm)

            And("A load balancer")
            var lb = createTopologyObject(classOf[Proto.LoadBalancer])
            store.create(lb)

            And("A pool")
            val pool = createTopologyObject(classOf[Proto.Pool], Map(
                "health_monitor_id" -> hm.getId,
                "load_balancer_id" -> lb.getId
            ))
            store.create(pool)

            And("A pool member")
            val poolMember = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> pool.getId,
                "weight" -> 1,
                "status" -> LBStatus.ACTIVE
            ))

            store.create(poolMember)

            And("A vip")
            val vip = createTopologyObject(classOf[Proto.VIP], Map(
                "pool_id" -> pool.getId,
                "load_balancer_id" -> lb.getId
            ))
            store.create(vip)
            lb = lb.toBuilder.addAllVipIds(List(vip.getId)).build
            store.update(lb)

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
            phm.poolMembers.map(_.id).toList shouldBe List(fromProto(poolMember.getId))
            phm.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The mapper emits a map on pool member update") {
            // Initial object creations
            val poolId = UUID.randomUUID()
            val lbId = UUID.randomUUID()

            val hm = createTopologyObject(classOf[Proto.HealthMonitor])
            val vip = createTopologyObject(classOf[Proto.VIP], Map(
                "pool_id" -> toProto(poolId),
                "load_balancer_id" -> toProto(lbId)))
            val lb = createTopologyObject(classOf[Proto.LoadBalancer], Map(
                "id" -> toProto(lbId),
                "vip_ids" -> Set(vip.getId)))
            val pool = createTopologyObject(classOf[Proto.Pool], Map(
                "id" -> toProto(poolId),
                "health_monitor_id" -> hm.getId,
                "load_balancer_id" -> toProto(lbId)
            ))
            val pm1 = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> toProto(poolId),
                "weight" -> 1,
                "status" -> LBStatus.ACTIVE
            ))

            store.create(hm)
            store.create(vip)
            store.create(lb)
            store.create(pool)
            store.create(pm1)

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
            val pmUpdated = pm1.toBuilder.setAdminStateUp(true).build()
            log.debug("new PM: " + pmUpdated)
            store.update(pmUpdated)

            // wait for an update (
            obs.awaitOnNext(1, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true
            val data = map(poolId)
            data.healthMonitor.id shouldBe fromProto(hm.getId)
            data.loadBalancer.id shouldBe lbId
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(pm1.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
            data.poolMembers.head.adminStateUp shouldBe true
        }

        scenario("The mapper emits a map on pool member addition") {
            // Initial object creations
            val poolId = UUID.randomUUID()
            val lbId = UUID.randomUUID()

            val hm = createTopologyObject(classOf[Proto.HealthMonitor])
            val vip = createTopologyObject(classOf[Proto.VIP], Map(
                "pool_id" -> toProto(poolId),
                "load_balancer_id" -> toProto(lbId)))
            val lb = createTopologyObject(classOf[Proto.LoadBalancer], Map(
                "id" -> toProto(lbId),
                "vip_ids" -> Set(vip.getId)))
            val pool = createTopologyObject(classOf[Proto.Pool], Map(
                "id" -> toProto(poolId),
                "health_monitor_id" -> hm.getId,
                "load_balancer_id" -> toProto(lbId)
            ))
            val pm1 = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> toProto(poolId),
                "weight" -> 1,
                "status" -> LBStatus.ACTIVE
            ))

            store.create(hm)
            store.create(vip)
            store.create(lb)
            store.create(pool)
            store.create(pm1)

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
            val pm2 = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> toProto(poolId),
                "weight" -> 1,
                "status" -> LBStatus.ACTIVE
            ))
            store.create(pm2)

            // wait for an update
            obs.awaitOnNext(1, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true
            map.contains(poolId) shouldBe true

            val data = map(poolId)
            data.healthMonitor.id shouldBe fromProto(hm.getId)
            data.loadBalancer.id shouldBe lbId
            data.poolMembers.map(_.id).toSet shouldBe
                Set(fromProto(pm1.getId), fromProto(pm2.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The mapper emits a map on pool member removal") {
            // Initial object creations
            val poolId = UUID.randomUUID()
            val lbId = UUID.randomUUID()

            val hm = createTopologyObject(classOf[Proto.HealthMonitor])
            val vip = createTopologyObject(classOf[Proto.VIP], Map(
                "pool_id" -> toProto(poolId),
                "load_balancer_id" -> toProto(lbId)))
            val lb = createTopologyObject(classOf[Proto.LoadBalancer], Map(
                "id" -> toProto(lbId),
                "vip_ids" -> Set(vip.getId)))
            val pool = createTopologyObject(classOf[Proto.Pool], Map(
                "id" -> toProto(poolId),
                "health_monitor_id" -> hm.getId,
                "load_balancer_id" -> toProto(lbId)
            ))
            val pm1 = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> toProto(poolId),
                "weight" -> 1,
                "status" -> LBStatus.ACTIVE
            ))

            store.create(hm)
            store.create(vip)
            store.create(lb)
            store.create(pool)
            store.create(pm1)

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
            map.contains(poolId) shouldBe true

            val data = map(poolId)
            data.healthMonitor.id shouldBe fromProto(hm.getId)
            data.loadBalancer.id shouldBe lbId
            data.poolMembers.map(_.id).toSet shouldBe Set()
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The mapping is removed on pool delete") {
            // Initial object creations
            val poolId = UUID.randomUUID()
            val lbId = UUID.randomUUID()

            val hm = createTopologyObject(classOf[Proto.HealthMonitor])
            val vip = createTopologyObject(classOf[Proto.VIP], Map(
                "pool_id" -> toProto(poolId),
                "load_balancer_id" -> toProto(lbId)))
            val lb = createTopologyObject(classOf[Proto.LoadBalancer], Map(
                "id" -> toProto(lbId),
                "vip_ids" -> Set(vip.getId)))
            val pool = createTopologyObject(classOf[Proto.Pool], Map(
                "id" -> toProto(poolId),
                "health_monitor_id" -> hm.getId,
                "load_balancer_id" -> toProto(lbId)
            ))
            val pm1 = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> toProto(poolId),
                "weight" -> 1,
                "status" -> LBStatus.ACTIVE
            ))

            store.create(hm)
            store.create(vip)
            store.create(lb)
            store.create(pool)
            store.create(pm1)

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
            val poolId = UUID.randomUUID()
            val lbId = UUID.randomUUID()

            val hm = createTopologyObject(classOf[Proto.HealthMonitor])
            val vip = createTopologyObject(classOf[Proto.VIP], Map(
                "pool_id" -> toProto(poolId),
                "load_balancer_id" -> toProto(lbId)))
            val lb = createTopologyObject(classOf[Proto.LoadBalancer], Map(
                "id" -> toProto(lbId),
                "vip_ids" -> Set(vip.getId)))
            val pool = createTopologyObject(classOf[Proto.Pool], Map(
                "id" -> toProto(poolId),
                "health_monitor_id" -> hm.getId,
                "load_balancer_id" -> toProto(lbId)
            ))
            val pm1 = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> toProto(poolId),
                "weight" -> 1,
                "status" -> LBStatus.ACTIVE
            ))

            store.create(hm)
            store.create(vip)
            store.create(lb)
            store.create(pool)
            store.create(pm1)

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
            val hmUpdated = hm.toBuilder.setMaxRetries(42).build()
            log.debug("new HM: " + hmUpdated)
            store.update(hmUpdated)

            // wait for an update (
            obs.awaitOnNext(1, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true
            val data = map(poolId)
            data.healthMonitor.id shouldBe fromProto(hm.getId)
            data.loadBalancer.id shouldBe lbId
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(pm1.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
            data.healthMonitor.maxRetries shouldBe 42
        }

        scenario("The mapper emits a map on health monitor change") {
            // Initial object creations
            val poolId = UUID.randomUUID()
            val lbId = UUID.randomUUID()

            val hm = createTopologyObject(classOf[Proto.HealthMonitor])
            val vip = createTopologyObject(classOf[Proto.VIP], Map(
                "pool_id" -> toProto(poolId),
                "load_balancer_id" -> toProto(lbId)))
            val lb = createTopologyObject(classOf[Proto.LoadBalancer], Map(
                "id" -> toProto(lbId),
                "vip_ids" -> Set(vip.getId)))
            val pool = createTopologyObject(classOf[Proto.Pool], Map(
                "id" -> toProto(poolId),
                "health_monitor_id" -> hm.getId,
                "load_balancer_id" -> toProto(lbId)
            ))
            val pm1 = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> toProto(poolId),
                "weight" -> 1,
                "status" -> LBStatus.ACTIVE
            ))

            store.create(hm)
            store.create(vip)
            store.create(lb)
            store.create(pool)
            store.create(pm1)

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
                Await.result(store.get(classOf[Proto.Pool], poolId), timeout)
            // Change the health monitor
            val hm2 = createTopologyObject(classOf[Proto.HealthMonitor])
            val poolUpdated = savedPool.toBuilder.setHealthMonitorId(hm2.getId).build()
            store.create(hm2)
            store.update(poolUpdated)

            // wait for an update (
            obs.awaitOnNext(1, timeout) shouldBe true
            // Depending on the timing, we may see an empty map
            // while waiting for the new health monitor data
            if (obs.getOnNextEvents.last.mappings.isEmpty)
                obs.awaitOnNext(2, timeout) shouldBe true

            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true

            val data = map(poolId)
            data.healthMonitor.id shouldBe fromProto(hm2.getId)
            data.loadBalancer.id shouldBe lbId
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(pm1.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The map is cleared if no health monitor is associated") {
            // Initial object creations
            val poolId = UUID.randomUUID()
            val lbId = UUID.randomUUID()

            val hm = createTopologyObject(classOf[Proto.HealthMonitor])
            val vip = createTopologyObject(classOf[Proto.VIP], Map(
                "pool_id" -> toProto(poolId),
                "load_balancer_id" -> toProto(lbId)))
            val lb = createTopologyObject(classOf[Proto.LoadBalancer], Map(
                "id" -> toProto(lbId),
                "vip_ids" -> Set(vip.getId)))
            val pool = createTopologyObject(classOf[Proto.Pool], Map(
                "id" -> toProto(poolId),
                "health_monitor_id" -> hm.getId,
                "load_balancer_id" -> toProto(lbId)
            ))
            val pm1 = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> toProto(poolId),
                "weight" -> 1,
                "status" -> LBStatus.ACTIVE
            ))

            store.create(hm)
            store.create(vip)
            store.create(lb)
            store.create(pool)
            store.create(pm1)

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

            // De-associate the health monitor
            val poolUpdated = pool.toBuilder.clearHealthMonitorId().build()
            store.update(poolUpdated)

            // wait for an update (
            obs.awaitOnNext(1, timeout) shouldBe true

            val map = obs.getOnNextEvents.last.mappings
            map.isEmpty shouldBe true
        }
    }
}
