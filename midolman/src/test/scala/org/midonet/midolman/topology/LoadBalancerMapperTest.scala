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

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.{CreateOp, NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{LoadBalancer => TopologyLb, Pool => TopologyPool, Vip}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{LoadBalancer => SimulationLb}
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class LoadBalancerMapperTest extends MidolmanSpec
                             with TopologyBuilder
                             with TopologyMatchers {

    import TopologyBuilder._

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private final val timeout = 5 seconds

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    implicit def asIPAddress(str: String): IPv4Addr = IPv4Addr(str)

    feature("The load-balancer mapper emits proper simulation objects") {
        scenario("The mapper emits error for non-existing load-balancers") {
            Given("A load-balancer identifier")
            val id = UUID.randomUUID

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(id, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.awaitCompletion(timeout)
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyLb]
            e.id shouldBe id
        }

        scenario("The mapper emits existing load-balancer") {
            Given("A load-balancer")
            val lb = createLoadBalancer(adminStateUp = Some(true))
            store.create(lb)

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a load-balancer")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            device shouldBeDeviceOf lb
        }

        scenario("The mapper emits new dvice on load-balancer update") {
            Given("A load-balancer")
            val lb1 = createLoadBalancer(adminStateUp = Some(true))
            store.create(lb1)

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb1.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a load-balancer")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The load-balancer is updated")
            val lb2 = lb1.setAdminStateUp(false)
            store.update(lb2)

            Then("The observer should receive the update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf lb2
        }

        scenario("The mapper completes when load-balancer is deleted") {
            Given("A load-balancer")
            val lb = createLoadBalancer(adminStateUp = Some(true))
            store.create(lb)

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a load-balancer")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The load-balancer is deleted")
            store.delete(classOf[TopologyLb], lb.getId)

            Then("The observer should receive a completed notification")
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should not be empty
        }
    }

    feature("The mapper does not emit updates for pools without VIPs") {
        scenario("Pool added") {
            Given("A load-balancer")
            val lb1 = createLoadBalancer(adminStateUp = Some(true))
            store.create(lb1)

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb1.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a load-balancer")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Adding a pool")
            val pool = createPool(loadBalancerId = Some(lb1.getId))
            store.create(pool)

            And("Updating the load-balancer to generate another update")
            val lb2 = lb1.setAdminStateUp(false)
            store.update(lb2)

            Then("The observer should receive only a load-balancer update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf lb2
        }

        scenario("Pool updated") {
            Given("A load-balancer with a pool")
            val lb1 = createLoadBalancer(adminStateUp = Some(true))
            val pool1 = createPool(loadBalancerId = Some(lb1.getId))
            store.multi(Seq(CreateOp(lb1), CreateOp(pool1)))

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb1.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a load-balancer")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Updating the pool")
            val pool2 = pool1.setAdminStateUp(true)
            store.update(pool2)

            And("Updating the load-balancer to generate another update")
            val lb2 = lb1.setAdminStateUp(false)
            store.update(lb2)

            Then("The observer should receive only a load-balancer update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf lb2
        }

        scenario("Pool deleted") {
            Given("A load-balancer with a pool")
            val lb1 = createLoadBalancer(adminStateUp = Some(true))
            val pool = createPool(loadBalancerId = Some(lb1.getId))
            store.multi(Seq(CreateOp(lb1), CreateOp(pool)))

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb1.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a load-balancer")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Deleting the pool")
            store.delete(classOf[TopologyPool], pool.getId)

            And("Updating the load-balancer to generate another update")
            val lb2 = lb1.setAdminStateUp(false)
            store.update(lb2)

            Then("The observer should receive only a load-balancer update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf lb2
        }
    }

    feature("The mapper emits updates for pools with VIPs") {
        scenario("Adding a pool with a VIP") {
            Given("A load-balancer")
            val lb = createLoadBalancer(adminStateUp = Some(true))
            store.create(lb)

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a load-balancer")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Adding a pool with a VIP")
            val pool = createPool(loadBalancerId = Some(lb.getId))
            val vip = createVip(poolId = Some(pool.getId),
                                address = Some("10.0.0.1"),
                                protocolPort = Some(1))
            store.multi(Seq(CreateOp(pool), CreateOp(vip)))

            Then("The observer should receive the load-balancer with the VIP")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf lb.addPool(pool.getId)
            device.vips(0) shouldBeDeviceOf vip
        }

        scenario("Updating a pool with a VIP") {
            Given("A load-balancer with a pool")
            val lb = createLoadBalancer(adminStateUp = Some(true))
            val pool = createPool(loadBalancerId = Some(lb.getId))
            store.multi(Seq(CreateOp(lb), CreateOp(pool)))

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a load-balancer without a VIP")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf lb.addPool(pool.getId)
            device1.vips shouldBe empty

            When("Adding a VIP to the pool")
            val vip = createVip(poolId = Some(pool.getId),
                                address = Some("10.0.0.1"),
                                protocolPort = Some(1))
            store.create(vip)

            Then("The observer should receive the load-balancer with the VIP")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf lb.addPool(pool.getId)
            device2.vips(0) shouldBeDeviceOf vip
        }

        scenario("Deleting a pool with a VIP") {
            Given("A load-balancer with a pool with a VIP")
            val lb = createLoadBalancer(adminStateUp = Some(true))
            val pool = createPool(loadBalancerId = Some(lb.getId))
            val vip = createVip(poolId = Some(pool.getId),
                                address = Some("10.0.0.1"),
                                protocolPort = Some(1))
            store.multi(Seq(CreateOp(lb), CreateOp(pool), CreateOp(vip)))

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a load-balancer with the VIP")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf lb.addPool(pool.getId)
            device1.vips(0) shouldBeDeviceOf vip

            When("Deleting the pool")
            store.delete(classOf[TopologyPool], pool.getId)

            Then("The observer should receive the load-balancer without the VIP")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf lb.addPool(pool.getId)
            device.vips shouldBe empty
        }

        scenario("Mapper ignores pool updates not affecting the VIPs") {
            Given("A load-balancer with a pool with a VIP")
            val lb1 = createLoadBalancer(adminStateUp = Some(true))
            val pool1 = createPool(loadBalancerId = Some(lb1.getId))
            val vip = createVip(poolId = Some(pool1.getId),
                                address = Some("10.0.0.1"),
                                protocolPort = Some(1))
            store.multi(Seq(CreateOp(lb1), CreateOp(pool1), CreateOp(vip)))

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb1.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a load-balancer with the VIP")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Updating the pool")
            val pool2 = pool1.setAdminStateUp(true).addVip(vip.getId)
            store.update(pool2)

            And("Updating the load-balancer to generate another update")
            val lb2 = lb1.setAdminStateUp(false)
            store.update(lb2)

            Then("The observer should receive only a load-balancer update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf lb2
        }

        scenario("VIPs are ordered by pool") {
            Given("A load-balancer with two pools each with one hundred VIPs")
            val lb = createLoadBalancer(adminStateUp = Some(true))
            val pool1 = createPool(loadBalancerId = Some(lb.getId))
            val pool2 = createPool(loadBalancerId = Some(lb.getId))
            store.multi(Seq(CreateOp(lb), CreateOp(pool1), CreateOp(pool2)))
            val vips1 = for (index <- 0 until 100) yield {
                val vip = createVip(poolId = Some(pool1.getId),
                                    address = Some("10.0.0.1"),
                                    protocolPort = Some(1))
                store.create(vip)
                vip
            }
            val vips2 = for (index <- 0 until 100) yield {
                val vip = createVip(poolId = Some(pool2.getId),
                                    address = Some("10.0.0.2"),
                                    protocolPort = Some(1))
                store.create(vip)
                vip
            }

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a load-balancer with the VIPs")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            for (index <- 0 until 100) {
                device.vips(index) shouldBeDeviceOf vips1(index)
                device.vips(index + 100) shouldBeDeviceOf vips2(index)
            }
        }
    }

    feature("The mapper emits updates for VIPs") {
        scenario("Adding a VIP to an existing pool") {
            Given("A load-balancer with a pool")
            val lb = createLoadBalancer(adminStateUp = Some(true))
            val pool = createPool(loadBalancerId = Some(lb.getId))
            store.multi(Seq(CreateOp(lb), CreateOp(pool)))

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a load-balancer")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Adding a VIP to the pool")
            val vip = createVip(poolId = Some(pool.getId),
                                address = Some("10.0.0.1"),
                                protocolPort = Some(1))
            store.create(vip)

            Then("The observer should receive the load-balancer with the VIP")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf lb.addPool(pool.getId)
            device.vips(0) shouldBeDeviceOf vip
        }

        scenario("Updating a VIP from an existing pool") {
            Given("A load-balancer with a pool with a VIP")
            val lb = createLoadBalancer(adminStateUp = Some(true))
            val pool = createPool(loadBalancerId = Some(lb.getId))
            val vip1 = createVip(poolId = Some(pool.getId),
                                 address = Some("10.0.0.1"),
                                 protocolPort = Some(1))
            store.multi(Seq(CreateOp(lb), CreateOp(pool), CreateOp(vip1)))

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the load-balancer with the VIP")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf lb.addPool(pool.getId)
            device1.vips(0) shouldBeDeviceOf vip1

            When("Updating the VIP")
            val vip2 = vip1.setAddress("10.0.0.2")
            store.update(vip2)

            Then("The observer should receive the load-balancer with the VIP")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf lb.addPool(pool.getId)
            device2.vips(0) shouldBeDeviceOf vip2
        }

        scenario("Re-assigning a VIP from an existing pool") {
            Given("A load-balancer with a pool with a VIP")
            val lb = createLoadBalancer(adminStateUp = Some(true))
            val pool = createPool(loadBalancerId = Some(lb.getId))
            val vip1 = createVip(poolId = Some(pool.getId),
                                 address = Some("10.0.0.1"),
                                 protocolPort = Some(1))
            store.multi(Seq(CreateOp(lb), CreateOp(pool), CreateOp(vip1)))

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the load-balancer with the VIP")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf lb.addPool(pool.getId)
            device1.vips(0) shouldBeDeviceOf vip1

            When("Updating the VIP by clearing the pool")
            val vip2 = vip1.clearPoolId()
            store.update(vip2)

            Then("The observer should receive the load-balancer without the VIP")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf lb.addPool(pool.getId)
            device2.vips shouldBe empty
        }

        scenario("Deleting a VIP from an existing pool") {
            Given("A load-balancer with a pool with a VIP")
            val lb = createLoadBalancer(adminStateUp = Some(true))
            val pool = createPool(loadBalancerId = Some(lb.getId))
            val vip = createVip(poolId = Some(pool.getId),
                                address = Some("10.0.0.1"),
                                protocolPort = Some(1))
            store.multi(Seq(CreateOp(lb), CreateOp(pool), CreateOp(vip)))

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the load-balancer with the VIP")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf lb.addPool(pool.getId)
            device1.vips(0) shouldBeDeviceOf vip

            When("Deleting the VIP")
            store.delete(classOf[Vip], vip.getId)

            Then("The observer should receive the load-balancer without the VIP")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf lb.addPool(pool.getId)
            device2.vips shouldBe empty
        }

        scenario("Mapper does not emit LB until all pools are loaded") {
            Given("A load-balancer with two pools")
            val lb = createLoadBalancer(adminStateUp = Some(true))
            val pool1 = createPool(loadBalancerId = Some(lb.getId))
            val pool2 = createPool(loadBalancerId = Some(lb.getId))
            store.multi(Seq(CreateOp(lb), CreateOp(pool1), CreateOp(pool2)))

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(lb.getId, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimulationLb](vt)

            When("Requesting the pools to have them cached in the store")
            val obsPool = new TestObserver[TopologyPool]
                             with AwaitableObserver[TopologyPool]
            vt.store.observable(classOf[TopologyPool], pool1.getId)
                .subscribe(obsPool)
            vt.store.observable(classOf[TopologyPool], pool2.getId)
                .subscribe(obsPool)
            obsPool.awaitOnNext(2, timeout)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the load-balancer with the pools")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            device shouldBeDeviceOf lb.addPool(pool1.getId)
                .addPool(pool2.getId)
        }
    }
}
