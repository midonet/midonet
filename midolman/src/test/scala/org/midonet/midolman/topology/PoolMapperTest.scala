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
import org.midonet.cluster.models.Commons.LBStatus
import org.midonet.cluster.models.Topology.{Pool => TopologyPool, Vip, PoolMember}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{Pool => SimulationPool}
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr
import org.midonet.util.reactivex.AwaitableObserver

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

    implicit def asIPAddress(str: String): IPv4Addr = IPv4Addr(str)

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

        scenario("The mapper completes when pool is deleted") {
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

    feature("The mapper emits updates for pool members") {
        scenario("Active pool member added") {
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

            When("Adding an active member to the pool")
            val member = createPoolMember(poolId = Some(pool.getId),
                                          adminStateUp = Some(true),
                                          status = Some(LBStatus.ACTIVE),
                                          weight = Some(1))
            store.create(member)

            Then("The observer should receive a pool update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf pool.addPoolMember(member.getId)
            device.members(0) shouldBeDeviceOf member
            device.activePoolMembers(0) shouldBeDeviceOf member
            device.disabledPoolMembers shouldBe empty
        }

        scenario("Active pool member updated") {
            Given("A pool with an active member")
            val pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(true),
                                           status = Some(LBStatus.ACTIVE),
                                           weight = Some(1))
            store.multi(Seq(CreateOp(pool), CreateOp(member1)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool.addPoolMember(member1.getId)
            device1.members(0) shouldBeDeviceOf member1
            device1.activePoolMembers(0) shouldBeDeviceOf member1
            device1.disabledPoolMembers shouldBe empty

            When("The member is updated")
            val member2 = member1.setAddress(IPv4Addr.random)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool.addPoolMember(member2.getId)
            device2.members(0) shouldBeDeviceOf member2
            device2.activePoolMembers(0) shouldBeDeviceOf member2
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Active pool member deleted") {
            Given("A pool with an active member")
            val pool = createPool()
            val member = createPoolMember(poolId = Some(pool.getId),
                                          adminStateUp = Some(true),
                                          status = Some(LBStatus.ACTIVE),
                                          weight = Some(1))
            store.multi(Seq(CreateOp(pool), CreateOp(member)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool.addPoolMember(member.getId)
            device1.members(0) shouldBeDeviceOf member
            device1.activePoolMembers(0) shouldBeDeviceOf member
            device1.disabledPoolMembers shouldBe empty

            When("The member is deleted")
            store.delete(classOf[PoolMember], member.getId)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.members shouldBe empty
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Inactive pool member added") {
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

            When("Adding an inactive member to the pool")
            val member = createPoolMember(poolId = Some(pool.getId),
                                          adminStateUp = Some(true),
                                          status = Some(LBStatus.INACTIVE),
                                          weight = Some(1))
            store.create(member)

            Then("The observer should receive a pool update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf pool.addPoolMember(member.getId)
            device.members(0) shouldBeDeviceOf member
            device.activePoolMembers shouldBe empty
            device.disabledPoolMembers shouldBe empty
        }

        scenario("Inactive pool member updated") {
            Given("A pool with an inactive member")
            val pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(true),
                                           status = Some(LBStatus.INACTIVE),
                                           weight = Some(1))
            store.multi(Seq(CreateOp(pool), CreateOp(member1)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool.addPoolMember(member1.getId)
            device1.members(0) shouldBeDeviceOf member1
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers shouldBe empty

            When("The member is updated")
            val member2 = member1.setAddress(IPv4Addr.random)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool.addPoolMember(member2.getId)
            device2.members(0) shouldBeDeviceOf member2
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Inactive pool member deleted") {
            Given("A pool with an inactive member")
            val pool = createPool()
            val member = createPoolMember(poolId = Some(pool.getId),
                                          adminStateUp = Some(true),
                                          status = Some(LBStatus.INACTIVE),
                                          weight = Some(1))
            store.multi(Seq(CreateOp(pool), CreateOp(member)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool.addPoolMember(member.getId)
            device1.members(0) shouldBeDeviceOf member
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers shouldBe empty

            When("The member is deleted")
            store.delete(classOf[PoolMember], member.getId)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.members shouldBe empty
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Disabled pool member added") {
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

            When("Adding a disabled member to the pool")
            val member = createPoolMember(poolId = Some(pool.getId),
                                          adminStateUp = Some(false))
            store.create(member)

            Then("The observer should receive a pool update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf pool.addPoolMember(member.getId)
            device.members(0) shouldBeDeviceOf member
            device.activePoolMembers shouldBe empty
            device.disabledPoolMembers(0) shouldBeDeviceOf member
        }

        scenario("Disabled pool member updated") {
            Given("A pool with a disabled member")
            val pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(false))
            store.multi(Seq(CreateOp(pool), CreateOp(member1)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool.addPoolMember(member1.getId)
            device1.members(0) shouldBeDeviceOf member1
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers(0) shouldBeDeviceOf member1

            When("The member is updated")
            val member2 = member1.setAddress(IPv4Addr.random)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool.addPoolMember(member2.getId)
            device2.members(0) shouldBeDeviceOf member2
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers(0) shouldBeDeviceOf member2
        }

        scenario("Disabled pool member deleted") {
            Given("A pool with a disabled member")
            val pool = createPool()
            val member = createPoolMember(poolId = Some(pool.getId),
                                          adminStateUp = Some(false))
            store.multi(Seq(CreateOp(pool), CreateOp(member)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool.addPoolMember(member.getId)
            device1.members(0) shouldBeDeviceOf member
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers(0) shouldBeDeviceOf member

            When("The member is deleted")
            store.delete(classOf[PoolMember], member.getId)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.members shouldBe empty
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Active pool member updates to inactive") {
            Given("A pool with an active member")
            val pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(true),
                                           status = Some(LBStatus.ACTIVE),
                                           weight = Some(1))
            store.multi(Seq(CreateOp(pool), CreateOp(member1)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool.addPoolMember(member1.getId)
            device1.members(0) shouldBeDeviceOf member1
            device1.activePoolMembers(0) shouldBeDeviceOf member1
            device1.disabledPoolMembers shouldBe empty

            When("The member is updated to inactive")
            val member2 = member1.setStatus(LBStatus.INACTIVE)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool.addPoolMember(member2.getId)
            device2.members(0) shouldBeDeviceOf member2
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Active pool member updates to disabled") {
            Given("A pool with an active member")
            val pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(true),
                                           status = Some(LBStatus.ACTIVE),
                                           weight = Some(1))
            store.multi(Seq(CreateOp(pool), CreateOp(member1)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool.addPoolMember(member1.getId)
            device1.members(0) shouldBeDeviceOf member1
            device1.activePoolMembers(0) shouldBeDeviceOf member1
            device1.disabledPoolMembers shouldBe empty

            When("The member is updated to disabled")
            val member2 = member1.setAdminStateUp(false)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool.addPoolMember(member2.getId)
            device2.members(0) shouldBeDeviceOf member2
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers(0) shouldBeDeviceOf member2
        }

        scenario("Inactive pool member updates to active") {
            Given("A pool with an inactive member")
            val pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(true),
                                           status = Some(LBStatus.INACTIVE),
                                           weight = Some(1))
            store.multi(Seq(CreateOp(pool), CreateOp(member1)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool.addPoolMember(member1.getId)
            device1.members(0) shouldBeDeviceOf member1
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers shouldBe empty

            When("The member is updated to active")
            val member2 = member1.setStatus(LBStatus.ACTIVE)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool.addPoolMember(member2.getId)
            device2.members(0) shouldBeDeviceOf member2
            device2.activePoolMembers(0) shouldBeDeviceOf member2
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Inactive pool member updates to disabled") {
            Given("A pool with an inactive member")
            val pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(true),
                                           status = Some(LBStatus.INACTIVE),
                                           weight = Some(1))
            store.multi(Seq(CreateOp(pool), CreateOp(member1)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool.addPoolMember(member1.getId)
            device1.members(0) shouldBeDeviceOf member1
            device1.activePoolMembers shouldBe empty
            device1.disabledPoolMembers shouldBe empty

            When("The member is updated to disabled")
            val member2 = member1.setAdminStateUp(false)
            store.update(member2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool.addPoolMember(member2.getId)
            device2.members(0) shouldBeDeviceOf member2
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers(0) shouldBeDeviceOf member2
        }

        scenario("Disabled pool member updates to active") {
            Given("A pool with a disabled member")
            val pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(false))
            store.multi(Seq(CreateOp(pool), CreateOp(member1)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool.addPoolMember(member1.getId)
            device1.members(0) shouldBeDeviceOf member1
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
            device2 shouldBeDeviceOf pool.addPoolMember(member2.getId)
            device2.members(0) shouldBeDeviceOf member2
            device2.activePoolMembers(0) shouldBeDeviceOf member2
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Disabled pool member updates to inactive") {
            Given("A pool with a disabled member")
            val pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(false))
            store.multi(Seq(CreateOp(pool), CreateOp(member1)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool.addPoolMember(member1.getId)
            device1.members(0) shouldBeDeviceOf member1
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
            device2 shouldBeDeviceOf pool.addPoolMember(member2.getId)
            device2.members(0) shouldBeDeviceOf member2
            device2.activePoolMembers shouldBe empty
            device2.disabledPoolMembers shouldBe empty
        }

        scenario("Mapper does not emit pool until all members are loaded") {
            Given("A pool with two members")
            val pool = createPool()
            val member1 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(false))
            val member2 = createPoolMember(poolId = Some(pool.getId),
                                           adminStateUp = Some(false))
            store.multi(Seq(CreateOp(pool), CreateOp(member1),
                            CreateOp(member2)))

            And("A pool observer")
            val obs = new DeviceObserver[SimulationPool](vt)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            When("Requesting the members to have them cached in the store")
            val obsMember = new TestObserver[PoolMember]
                                with AwaitableObserver[PoolMember]
            vt.store.observable(classOf[PoolMember], member1.getId)
                .subscribe(obsMember)
            vt.store.observable(classOf[PoolMember], member2.getId)
                .subscribe(obsMember)
            obsMember.awaitOnNext(2, timeout)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the mapper device")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            device shouldBeDeviceOf pool.addPoolMember(member1.getId)
                                        .addPoolMember(member2.getId)
        }

        scenario("Pool members are in order") {
            Given("A pool with one hundred members")
            val pool = createPool()
            store.create(pool)

            val members = for (index <- 0 until 100) yield {
                val member = createPoolMember(poolId = Some(pool.getId),
                                              adminStateUp = Some(false))
                store.create(member)
                member
            }

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            for (index <- 0 until 100)
                device.members(index) shouldBeDeviceOf members(index)
        }
    }

    feature("The pool emits updates for VIPs") {
        scenario("VIP added") {
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

            When("Adding a VIP to the pool")
            val vip = createVip(poolId = Some(pool.getId),
                                address = Some("10.0.0.1"),
                                protocolPort = Some(1))
            store.create(vip)

            Then("The observer should receive a pool update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf pool
            device.vips(0) shouldBeDeviceOf vip
        }

        scenario("VIP updated") {
            Given("A pool with an active member")
            val pool = createPool()
            val vip1 = createVip(poolId = Some(pool.getId),
                                 address = Some("10.0.0.1"),
                                 protocolPort = Some(1))
            store.multi(Seq(CreateOp(pool), CreateOp(vip1)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a VIP")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.vips(0) shouldBeDeviceOf vip1

            When("The VIP is updated")
            val vip2 = vip1.setAddress("10.0.0.2")
            store.update(vip2)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.vips(0) shouldBeDeviceOf vip2
        }

        scenario("VIP deleted") {
            Given("A pool with an active member")
            val pool = createPool()
            val vip = createVip(poolId = Some(pool.getId),
                                address = Some("10.0.0.1"),
                                protocolPort = Some(1))
            store.multi(Seq(CreateOp(pool), CreateOp(vip)))

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a VIP")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf pool
            device1.vips(0) shouldBeDeviceOf vip

            When("The VIP is deleted")
            store.delete(classOf[Vip], vip.getId)

            Then("The observer should receive an updated pool")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf pool
            device2.vips shouldBe empty
        }

        scenario("Mapper does not emit pool until all VIPs are loaded") {
            Given("A pool with two VIPs")
            val pool = createPool()
            val vip1 = createVip(poolId = Some(pool.getId),
                                 address = Some("10.0.0.1"),
                                 protocolPort = Some(1))
            val vip2 = createVip(poolId = Some(pool.getId),
                                 address = Some("10.0.0.2"),
                                 protocolPort = Some(2))
            store.multi(Seq(CreateOp(pool), CreateOp(vip1), CreateOp(vip2)))

            And("A pool observer")
            val obs = new DeviceObserver[SimulationPool](vt)

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            When("Requesting the members to have them cached in the store")
            val obsVip = new TestObserver[Vip]
                             with AwaitableObserver[Vip]
            vt.store.observable(classOf[Vip], vip1.getId).subscribe(obsVip)
            vt.store.observable(classOf[Vip], vip2.getId).subscribe(obsVip)
            obsVip.awaitOnNext(2, timeout)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the mapper device")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            device shouldBeDeviceOf pool.addVip(vip1.getId).addVip(vip2.getId)
        }

        scenario("VIPs are in order") {
            Given("A pool with one hundred VIPs")
            val pool = createPool()
            store.create(pool)

            val vips = for (index <- 0 until 100) yield {
                val vip = createVip(poolId = Some(pool.getId),
                                    address = Some("10.0.0.1"),
                                    protocolPort = Some(1))
                store.create(vip)
                vip
            }

            And("A pool mapper")
            val mapper = new PoolMapper(pool.getId, vt)

            And("An observer to the pool mapper")
            val obs = new DeviceObserver[SimulationPool](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool with a member")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            for (index <- 0 until 100)
                device.vips(index) shouldBeDeviceOf vips(index)
        }
    }
}
