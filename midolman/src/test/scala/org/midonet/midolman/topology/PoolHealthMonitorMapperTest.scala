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

import org.midonet.cluster.data.storage.{CreateOp, Storage, UpdateOp}
import org.midonet.cluster.models.Commons.LBStatus
import org.midonet.cluster.models.Topology.{PoolMember, Pool}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder._
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil._
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

    implicit def asIPAddress(str: String): IPv4Addr = IPv4Addr(str)

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
            obs.getOnNextEvents.get(0).mappings shouldBe empty
        }

        scenario("The mapper emits an existing pool health monitor map") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor = createHealthMonitor()
            val loadBalancer = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(healthMonitor.getId),
                                  loadBalancerId = Some(loadBalancer.getId))
            val poolMember = createPoolMember(poolId = Some(pool.getId),
                                              status = Some(LBStatus.ACTIVE),
                                              weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId))
            store.multi(Seq(CreateOp(healthMonitor), CreateOp(loadBalancer),
                            CreateOp(pool), CreateOp(poolMember), CreateOp(vip)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true

            val map = obs.getOnNextEvents.get(0).mappings
            map should not be empty
            map should contain key fromProto(pool.getId)

            val phm = map(fromProto(pool.getId))
            phm.healthMonitor.id shouldBe fromProto(healthMonitor.getId)
            phm.loadBalancer.id shouldBe fromProto(loadBalancer.getId)
            phm.poolMembers.map(_.id).toList shouldBe List(fromProto(poolMember.getId))
            phm.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The mapper emits a map on pool member update") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor = createHealthMonitor()
            val loadBalancer = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(healthMonitor.getId),
                                  loadBalancerId = Some(loadBalancer.getId))
            val poolMember1 = createPoolMember(poolId = Some(pool.getId),
                                               status = Some(LBStatus.ACTIVE),
                                               weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId))

            store.multi(Seq(CreateOp(healthMonitor), CreateOp(loadBalancer),
                            CreateOp(pool), CreateOp(poolMember1),
                            CreateOp(vip)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0).mappings should not be empty

            When("Updating the pool member")
            val poolMember2 = poolMember1.setAdminStateUp(true)
            store.update(poolMember2)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(2, timeout) shouldBe true
            val map = obs.getOnNextEvents.get(1).mappings
            map should not be empty

            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf healthMonitor
            data.loadBalancer.id shouldBe fromProto(loadBalancer.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(poolMember1.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
            data.poolMembers.head.adminStateUp shouldBe true
        }

        scenario("The mapper emits a map on pool member addition") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor = createHealthMonitor()
            val loadBalancer = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(healthMonitor.getId),
                                  loadBalancerId = Some(loadBalancer.getId))
            val poolMember1 = createPoolMember(poolId = Some(pool.getId),
                                               status = Some(LBStatus.ACTIVE),
                                               weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId))

            store.multi(Seq(CreateOp(healthMonitor), CreateOp(loadBalancer),
                            CreateOp(pool), CreateOp(poolMember1),
                            CreateOp(vip)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0).mappings should not be empty

            When("Creating a new pool member")
            val poolMember2 = createPoolMember(poolId = Some(pool.getId),
                                               status = Some(LBStatus.ACTIVE),
                                               weight = Some(1))
            store.create(poolMember2)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(2, timeout) shouldBe true
            val map = obs.getOnNextEvents.get(1).mappings
            map should not be empty
            map should contain key pool.getId.asJava

            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf healthMonitor
            data.loadBalancer.id shouldBe fromProto(loadBalancer.getId)
            data.poolMembers.map(_.id).toSet shouldBe
                Set(fromProto(poolMember1.getId), fromProto(poolMember2.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The mapper emits a map on pool member removal") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor = createHealthMonitor()
            val loadBalancer = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(healthMonitor.getId),
                                  loadBalancerId = Some(loadBalancer.getId))
            val poolMember = createPoolMember(poolId = Some(pool.getId),
                                              status = Some(LBStatus.ACTIVE),
                                              weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId))

            store.multi(Seq(CreateOp(healthMonitor), CreateOp(loadBalancer),
                            CreateOp(pool), CreateOp(poolMember),
                            CreateOp(vip)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0).mappings should not be empty

            When("Deleting the pool member")
            store.delete(classOf[PoolMember], poolMember.getId)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(2, timeout) shouldBe true
            val map = obs.getOnNextEvents.get(1).mappings
            map should not be empty
            map should contain key pool.getId.asJava

            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf healthMonitor
            data.loadBalancer.id shouldBe fromProto(loadBalancer.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set()
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The mapping is removed on pool delete") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor = createHealthMonitor()
            val loadBalancer = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(healthMonitor.getId),
                                  loadBalancerId = Some(loadBalancer.getId))
            val poolMember = createPoolMember(poolId = Some(pool.getId),
                                              status = Some(LBStatus.ACTIVE),
                                              weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId))

            store.multi(Seq(CreateOp(healthMonitor), CreateOp(loadBalancer),
                            CreateOp(pool), CreateOp(poolMember),
                            CreateOp(vip)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0).mappings should not be empty

            When("Deleting the pool")
            store.delete(classOf[Pool], pool.getId)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1).mappings shouldBe empty
        }

        scenario("The mapper emits a map on health monitor update") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor1 = createHealthMonitor()
            val loadBalancer = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(healthMonitor1.getId),
                                  loadBalancerId = Some(loadBalancer.getId))
            val poolMember = createPoolMember(poolId = Some(pool.getId),
                                              status = Some(LBStatus.ACTIVE),
                                              weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId))

            store.multi(Seq(CreateOp(healthMonitor1), CreateOp(loadBalancer),
                            CreateOp(pool), CreateOp(poolMember),
                            CreateOp(vip)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0).mappings should not be empty

            When("Updating the health monitor")
            val healthMonitor2 = healthMonitor1
                .setMaxRetries(42).setPoolId(pool.getId)
            store.update(healthMonitor2)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(2, timeout) shouldBe true
            val map = obs.getOnNextEvents.get(1).mappings
            map should not be empty

            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf healthMonitor2
            data.loadBalancer.id shouldBe fromProto(loadBalancer.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(poolMember.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
            data.healthMonitor.maxRetries shouldBe 42
        }

        scenario("The mapper emits a map on health monitor change") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor1 = createHealthMonitor()
            val loadBalancer = createLoadBalancer()
            val pool1 = createPool(healthMonitorId = Some(healthMonitor1.getId),
                                  loadBalancerId = Some(loadBalancer.getId))
            val poolMember = createPoolMember(poolId = Some(pool1.getId),
                                       status = Some(LBStatus.ACTIVE),
                                       weight = Some(1))
            val vip = createVip(poolId = Some(pool1.getId))

            store.multi(Seq(CreateOp(healthMonitor1), CreateOp(loadBalancer),
                            CreateOp(pool1), CreateOp(poolMember),
                            CreateOp(vip)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0).mappings should not be empty

            When("Creating a new health monitor")
            val healthMonitor2 = createHealthMonitor()
            store.create(healthMonitor2)

            And("Adding the pool to it")
            val pool2 = pool1.addPoolMember(poolMember.getId).addVip(vip.getId)
                .setHealthMonitorId(healthMonitor2.getId)
            store.update(pool2)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(3, timeout) shouldBe true
            val map = obs.getOnNextEvents.last.mappings
            map should not be empty

            val data = map(pool2.getId)
            data.healthMonitor shouldBeDeviceOf healthMonitor2
            data.loadBalancer.id shouldBe fromProto(loadBalancer.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(poolMember.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The map is cleared if no health monitor is associated") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor = createHealthMonitor()
            val loadBalancer = createLoadBalancer()
            val pool1 = createPool(healthMonitorId = Some(healthMonitor.getId),
                                  loadBalancerId = Some(loadBalancer.getId))
            val poolMember = createPoolMember(poolId = Some(pool1.getId),
                                              status = Some(LBStatus.ACTIVE),
                                              weight = Some(1))
            val vip = createVip(poolId = Some(pool1.getId))

            store.multi(Seq(CreateOp(healthMonitor), CreateOp(loadBalancer),
                            CreateOp(pool1), CreateOp(poolMember),
                            CreateOp(vip)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0).mappings should not be empty

            When("Removing the pool from the health monitor")
            val pool2 = pool1.removeHealthMonitorId()
                .addPoolMember(poolMember.getId).addVip(vip.getId)
            store.update(pool2)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2).mappings shouldBe empty
        }

        scenario("The mapper emits a map on load balancer update") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor = createHealthMonitor()
            val loadBalancer1 = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(healthMonitor.getId),
                                  loadBalancerId = Some(loadBalancer1.getId))
            val poolMember = createPoolMember(poolId = Some(pool.getId),
                                              status = Some(LBStatus.ACTIVE),
                                              weight = Some(1))
            val vip = createVip(poolId = Some(pool.getId))

            store.multi(Seq(CreateOp(healthMonitor), CreateOp(loadBalancer1),
                            CreateOp(pool), CreateOp(poolMember),
                            CreateOp(vip)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0).mappings should not be empty

            When("Updating the load balancer")
            val loadBalancer2 = loadBalancer1.addPool(pool.getId)
                .setAdminStateUp(true)
            store.update(loadBalancer2)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(2, timeout) shouldBe true
            val map = obs.getOnNextEvents.get(1).mappings
            map should not be empty

            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf healthMonitor
            data.loadBalancer.id shouldBe fromProto(loadBalancer1.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(poolMember.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
            data.loadBalancer.adminStateUp shouldBe true
        }

        scenario("The mapper emits a map on load balancer change") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor = createHealthMonitor()
            val loadBalancer1 = createLoadBalancer()
            val pool1 = createPool(healthMonitorId = Some(healthMonitor.getId),
                                  loadBalancerId = Some(loadBalancer1.getId))
            val poolMember = createPoolMember(poolId = Some(pool1.getId),
                                              status = Some(LBStatus.ACTIVE),
                                              weight = Some(1))
            val vip = createVip(poolId = Some(pool1.getId))

            store.multi(Seq(CreateOp(healthMonitor), CreateOp(loadBalancer1),
                            CreateOp(pool1), CreateOp(poolMember),
                            CreateOp(vip)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0).mappings should not be empty

            When("Adding the pool to a different load-balancer")
            val loadBalancer2 = createLoadBalancer()
            val pool2 = pool1.setLoadBalancerId(loadBalancer2.getId)
                .addPoolMember(poolMember.getId).addVip(vip.getId)
            store.multi(Seq(CreateOp(loadBalancer2), UpdateOp(pool2)))

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(4, timeout) shouldBe true

            val map = obs.getOnNextEvents.get(3).mappings
            map should not be empty

            val data = map(pool2.getId)
            data.healthMonitor shouldBeDeviceOf healthMonitor
            data.loadBalancer.id shouldBe fromProto(loadBalancer2.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(poolMember.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip.getId))
        }

        scenario("The map is cleared if no load balancer is associated") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor = createHealthMonitor()
            val loadBalancer = createLoadBalancer()
            val pool1 = createPool(healthMonitorId = Some(healthMonitor.getId),
                                  loadBalancerId = Some(loadBalancer.getId))
            val poolMember = createPoolMember(poolId = Some(pool1.getId),
                                              status = Some(LBStatus.ACTIVE),
                                              weight = Some(1))
            val vip = createVip(poolId = Some(pool1.getId))

            store.multi(Seq(CreateOp(healthMonitor), CreateOp(loadBalancer),
                            CreateOp(pool1), CreateOp(poolMember),
                            CreateOp(vip)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0).mappings should not be empty

            When("Removing the pool from the load-balancer")
            val pool2 = pool1.removeLoadBalancerId()
                .addPoolMember(poolMember.getId).addVip(vip.getId)
            store.update(pool2)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2).mappings shouldBe empty
        }

        scenario("The mapper emits a map on VIP update") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor = createHealthMonitor()
            val loadBalancer = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(healthMonitor.getId),
                                  loadBalancerId = Some(loadBalancer.getId))
            val poolMember = createPoolMember(poolId = Some(pool.getId),
                                              status = Some(LBStatus.ACTIVE),
                                              weight = Some(1))
            val vip1 = createVip(poolId = Some(pool.getId))

            store.multi(Seq(CreateOp(healthMonitor), CreateOp(loadBalancer),
                            CreateOp(pool), CreateOp(poolMember),
                            CreateOp(vip1)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0).mappings should not be empty

            When("Updating the VIP")
            val vip2 = vip1.setAddress("10.0.0.1")
            store.update(vip2)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(2, timeout) shouldBe true
            val map = obs.getOnNextEvents.get(1).mappings
            map should not be empty

            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf healthMonitor
            data.loadBalancer.id shouldBe fromProto(loadBalancer.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(poolMember.getId))
            data.vips.map(_.id).toList shouldBe List(fromProto(vip2.getId))
            data.vips.head.address shouldBe IPv4Addr.fromString("10.0.0.1")
        }

        scenario("The mapper emits a map on vip addition") {
            Given("A health monitor, load balancer, pool, pool member and VIP")
            val healthMonitor = createHealthMonitor()
            val loadBalancer = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(healthMonitor.getId),
                                  loadBalancerId = Some(loadBalancer.getId))
            val poolMember = createPoolMember(poolId = Some(pool.getId),
                                              status = Some(LBStatus.ACTIVE),
                                              weight = Some(1))
            val vip1 = createVip(poolId = Some(pool.getId))

            store.multi(Seq(CreateOp(healthMonitor), CreateOp(loadBalancer),
                            CreateOp(pool), CreateOp(poolMember),
                            CreateOp(vip1)))

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0).mappings should not be empty

            When("Adding a second VIP to the pool")
            val vip2 = createVip(poolId = Some(pool.getId))
            store.create(vip2)

            Then("The observer should receive a pool health monitor map")
            obs.awaitOnNext(2, timeout) shouldBe true

            val map = obs.getOnNextEvents.get(1).mappings
            map should not be empty

            val data = map(pool.getId)
            data.healthMonitor shouldBeDeviceOf healthMonitor
            data.loadBalancer.id shouldBe fromProto(loadBalancer.getId)
            data.poolMembers.map(_.id).toSet shouldBe Set(fromProto(poolMember.getId))
            data.vips.map(_.id).toSet shouldBe
                Set(fromProto(vip1.getId), fromProto(vip2.getId))
        }
    }
}
