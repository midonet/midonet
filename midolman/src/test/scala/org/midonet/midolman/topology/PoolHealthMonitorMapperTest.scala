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
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.Observable

import org.midonet.cluster.data.storage.{TestAwaitableObserver, Storage}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.models.{Topology => Proto}
import org.midonet.cluster.util.UUIDUtil.fromProto
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
            var pool = createTopologyObject(classOf[Proto.Pool], Map(
                "health_monitor_id" -> hm.getId,
                "load_balancer_id" -> lb.getId
            ))
            store.create(pool)

            And("A pool member")
            val poolMember = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> pool.getId
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

        scenario("The mapper emits new map on pool update") {
            // Initial object creations
            val hm = createTopologyObject(classOf[Proto.HealthMonitor])
            store.create(hm)
            var lb = createTopologyObject(classOf[Proto.LoadBalancer])
            store.create(lb)
            var pool = createTopologyObject(classOf[Proto.Pool], Map(
                "health_monitor_id" -> hm.getId,
                "load_balancer_id" -> lb.getId,
                "admin_state_up" -> false
            ))
            store.create(pool)
            val pm1 = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> pool.getId
            ))
            store.create(pm1)
            And("A vip")
            val vip = createTopologyObject(classOf[Proto.VIP], Map(
                "pool_id" -> pool.getId,
                "load_balancer_id" -> lb.getId
            ))
            store.create(vip)
            lb = lb.toBuilder.addAllVipIds(List(vip.getId)).build
            store.update(lb)

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

            // update the pool (add a pool member)
            val pm2 = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> pool.getId
            ))
            store.create(pm2)

            // wait for an update
            obs.awaitOnNext(1, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map.nonEmpty shouldBe true
            map.contains(fromProto(pool.getId)) shouldBe true

            val phm = map(fromProto(pool.getId))
            phm.healthMonitor.id shouldBe fromProto(hm.getId)
            phm.loadBalancer.id shouldBe fromProto(lb.getId)
            phm.poolMembers.map(_.id).toSet shouldBe
                Set(fromProto(pm1.getId), fromProto(pm2.getId))
            phm.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The mapping is removed on pool delete") {
            // Initial object creations
            val hm = createTopologyObject(classOf[Proto.HealthMonitor])
            store.create(hm)
            var lb = createTopologyObject(classOf[Proto.LoadBalancer])
            store.create(lb)
            var pool = createTopologyObject(classOf[Proto.Pool], Map(
                "health_monitor_id" -> hm.getId,
                "load_balancer_id" -> lb.getId,
                "admin_state_up" -> false
            ))
            store.create(pool)
            val pm1 = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> pool.getId
            ))
            store.create(pm1)
            And("A vip")
            val vip = createTopologyObject(classOf[Proto.VIP], Map(
                "pool_id" -> pool.getId,
                "load_balancer_id" -> lb.getId
            ))
            store.create(vip)
            lb = lb.toBuilder.addAllVipIds(List(vip.getId)).build
            store.update(lb)

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

            // remove the pool member before removing the pool
            store.delete(classOf[Proto.PoolMember], pm1.getId)
            obs.awaitOnNext(1, timeout) shouldBe true

            // remove the pool
            store.delete(classOf[Proto.Pool], pool.getId)

            // wait for an update (
            obs.awaitOnNext(2, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map.isEmpty shouldBe true
        }
    }
}
