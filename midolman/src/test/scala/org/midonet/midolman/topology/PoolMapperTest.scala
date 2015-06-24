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

import scala.concurrent.Await
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.Observable

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Commons.LBStatus
import org.midonet.cluster.models.Topology.{Pool => TopologyPool, PoolMember}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{Pool => SimulationPool}
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class PoolMapperTest extends MidolmanSpec with TopologyBuilder
                     with TopologyMatchers {

    import TopologyBuilder._

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private final val timeout = 5 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    feature("The pool mapper emits pool devices") {
        scenario("The mapper emits error for non-existing pool") {
            Given("A pool identifier")
            val id = UUID.randomUUID

            And("A pool mapper")
            val mapper = new PoolMapper(id, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.awaitCompletion(timeout)
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyPool]
            e.id shouldBe id
        }

        scenario("The mapper emits existing pool") {
            Given("A pool")
            val pool = createPool()
            store.create(pool)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            device shouldBeDeviceOf pool
        }

        scenario("The mapper emits new device on pool update") {
            Given("A pool")
            val pool1 = createPool()
            store.create(pool1)

            And("A pool mapper")
            val mapper = new PoolMapper(pool1.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The pool is updated with a Health Monitor associated")
            val hm = createHealthMonitor()
            store.create(hm)
            val pool2 = pool1
                .setAdminStateUp(true)
                .setHealthMonitorId(hm.getId)
            store.update(pool2)

            Then("The observer should receive the update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf pool2
        }

        scenario("The mapper completes on port delete") {
            Given("A pool")
            val pool = createPool()
            store.create(pool)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The pool is deleted")
            store.delete(classOf[TopologyPool], pool.getId)

            Then("The observer should receive a completed notification")
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should not be empty
        }
    }

    feature("The pool emits updates for pool members") {
        scenario("Active pool member added") {
            Given("A pool")
            var pool = createPool()
            store.create(pool)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Adding an active member to the pool")
            val member = createPoolMember(poolId = Some(pool.getId),
                                          adminStateUp = Some(true),
                                          status = Some(LBStatus.ACTIVE),
                                          weight = Some(1))
            store.create(member)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)
            Then("The observer should receive a pool update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf pool
            device.activePoolMembers(0) shouldBeDeviceOf member
            device.disabledPoolMembers shouldBe empty
        }

        scenario("Active pool member updated") {
            Given("A pool with an active member")
            var pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(true),
                                           status = Some(LBStatus.ACTIVE),
                                           weight = Some(1))
            store.create(pool)
            store.create(member1)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.activePoolMembers(0) shouldBeDeviceOf member1
            device1.disabledPoolMembers shouldBe empty

            When("The member is updated")
            val member2 = member1.setAddress(IPv4Addr.random)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.activePoolMembers(0) shouldBeDeviceOf member2
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Active pool member deleted") {
            Given("A pool with an active member")
            var pool = createPool()
            val member = createPoolMember(poolId = Some(pool.getId),
                                          adminStateUp = Some(true),
                                          status = Some(LBStatus.ACTIVE),
                                          weight = Some(1))
            store.create(pool)
            store.create(member)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.activePoolMembers(0) shouldBeDeviceOf member
            device1.disabledPoolMembers shouldBe empty

            When("The member is deleted")
            store.delete(classOf[PoolMember], member.getId)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Inactive pool member added") {
            Given("A pool")
            var pool = createPool()
            store.create(pool)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Adding an inactive member to the pool")
            val member = createPoolMember(poolId = Some(pool.getId),
                                          adminStateUp = Some(true),
                                          status = Some(LBStatus.INACTIVE),
                                          weight = Some(1))
            store.create(member)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            Then("The observer should receive a pool update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf pool
            device.activePoolMembers shouldBe empty
            device.disabledPoolMembers shouldBe empty
        }

        scenario("Inactive pool member updated") {
            Given("A pool with an inactive member")
            var pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(true),
                                           status = Some(LBStatus.INACTIVE),
                                           weight = Some(1))
            store.create(pool)
            store.create(member1)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers shouldBe empty

            When("The member is updated")
            val member2 = member1.setAddress(IPv4Addr.random)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Inactive pool member deleted") {
            Given("A pool with an inactive member")
            var pool = createPool()
            val member = createPoolMember(poolId = Some(pool.getId),
                                          adminStateUp = Some(true),
                                          status = Some(LBStatus.INACTIVE),
                                          weight = Some(1))
            store.create(pool)
            store.create(member)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers shouldBe empty

            When("The member is deleted")
            store.delete(classOf[PoolMember], member.getId)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Disabled pool member added") {
            Given("A pool")
            var pool = createPool()
            store.create(pool)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Adding a disabled member to the pool")
            val member = createPoolMember(poolId = Some(pool.getId),
                                          adminStateUp = Some(false))
            store.create(member)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            Then("The observer should receive a pool update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf pool
            device.activePoolMembers shouldBe empty
            device.disabledPoolMembers(0) shouldBeDeviceOf member
        }

        scenario("Disabled pool member updated") {
            Given("A pool with a disabled member")
            var pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(false))
            store.create(pool)
            store.create(member1)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers(0) shouldBeDeviceOf member1

            When("The member is updated")
            val member2 = member1.setAddress(IPv4Addr.random)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers(0) shouldBeDeviceOf member2
        }

        scenario("Disabled pool member deleted") {
            Given("A pool with a disabled member")
            var pool = createPool()
            val member = createPoolMember(poolId = Some(pool.getId),
                                          adminStateUp = Some(false))
            store.create(pool)
            store.create(member)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers(0) shouldBeDeviceOf member

            When("The member is deleted")
            store.delete(classOf[PoolMember], member.getId)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Active pool member updates to inactive") {
            Given("A pool with an active member")
            var pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(true),
                                           status = Some(LBStatus.ACTIVE),
                                           weight = Some(1))
            store.create(pool)
            store.create(member1)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.activePoolMembers(0) shouldBeDeviceOf member1
            device1.disabledPoolMembers shouldBe empty

            When("The member is updated to inactive")
            val member2 = member1.setStatus(LBStatus.INACTIVE)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Active pool member updates to disabled") {
            Given("A pool with an active member")
            var pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(true),
                                           status = Some(LBStatus.ACTIVE),
                                           weight = Some(1))
            store.create(pool)
            store.create(member1)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.activePoolMembers(0) shouldBeDeviceOf member1
            device1.disabledPoolMembers shouldBe empty

            When("The member is updated to disabled")
            val member2 = member1.setAdminStateUp(false)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers(0) shouldBeDeviceOf member2
        }

        scenario("Inactive pool member updates to active") {
            Given("A pool with an inactive member")
            var pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(true),
                                           status = Some(LBStatus.INACTIVE),
                                           weight = Some(1))
            store.create(pool)
            store.create(member1)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers shouldBe empty

            When("The member is updated to active")
            val member2 = member1.setStatus(LBStatus.ACTIVE)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.activePoolMembers(0) shouldBeDeviceOf member2
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Inactive pool member updates to disabled") {
            Given("A pool with an inactive member")
            var pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(true),
                                           status = Some(LBStatus.INACTIVE),
                                           weight = Some(1))
            store.create(pool)
            store.create(member1)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers shouldBe empty

            When("The member is updated to disabled")
            val member2 = member1.setAdminStateUp(false)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers(0) shouldBeDeviceOf member2
        }

        scenario("Disabled pool member updates to active") {
            Given("A pool with a disabled member")
            var pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(false))
            store.create(pool)
            store.create(member1)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers(0) shouldBeDeviceOf member1

            When("The member is updated to active")
            val member2 = member1
                .setAdminStateUp(true)
                .setStatus(LBStatus.ACTIVE)
                .setWeight(1)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.activePoolMembers(0) shouldBeDeviceOf member2
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Disabled pool member updates to inactive") {
            Given("A pool with a disabled member")
            var pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(false))
            store.create(pool)
            store.create(member1)
            pool = Await.result(store.get(classOf[TopologyPool], pool.getId),
                                timeout)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers(0) shouldBeDeviceOf member1

            When("The member is updated to active")
            val member2 = member1
                .setAdminStateUp(true)
                .setStatus(LBStatus.INACTIVE)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers shouldBe empty
        }
    }
}
