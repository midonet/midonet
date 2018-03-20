/*
 * Copyright 2018 Midokura SARL
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

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Neutron.{HostPortBinding => TopologyHostPortBinding}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil.{toProto, fromProto}
import org.midonet.midolman.simulation.{HostPortBinding => SimulationHostPortBinding}
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.MidonetEventually
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class HostPortBindingMapperTest extends MidolmanSpec with TopologyBuilder
                       with TopologyMatchers with MidonetEventually {

    private type HostPortBindingObserver = TestObserver[SimulationHostPortBinding]
        with AwaitableObserver[SimulationHostPortBinding]

    private var store: Storage = _
    private var vt: VirtualTopology = _
    private var threadId: Long = _

    private final val timeout = 5 seconds

    private var toPort: UUID = _

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        threadId = Thread.currentThread.getId
    }

    private def createObserver(): DeviceObserver[SimulationHostPortBinding] = {
        Given("An observer for the host port binding mapper")
        // It is possible to receive the initial notification on the current
        // thread, when the device was notified in the mapper's behavior subject
        // previous to the subscription.
        new DeviceObserver[SimulationHostPortBinding](vt)
    }

    private def testHostPortBindingCreated(id: UUID, obs: HostPortBindingObserver): TopologyHostPortBinding = {
        Given("A host port binding mapper")
        val mapper = new HostPortBindingMapper(id, vt)

        And("A host port binding")
        val binding = createHostPortBinding(id, "eth0")

        When("The host port binding is created")
        store.create(binding)

        And("The observer subscribes to an observable on the mapper")
        Observable.create(mapper).subscribe(obs)


        Then("The observer should receive the host port binding device")
        obs.awaitOnNext(1, timeout) shouldBe true
        val device = obs.getOnNextEvents.get(0)
        device shouldBeDeviceOf binding

        binding
    }

    private def testHostPortBindingUpdated(hostPortBinding: TopologyHostPortBinding, obs: HostPortBindingObserver,
                                  event: Int): SimulationHostPortBinding = {
        When("The host port binding is updated")
        store.update(hostPortBinding)

        Then("The observer should receive the update")
        obs.awaitOnNext(event, timeout) shouldBe true
        val device = obs.getOnNextEvents.get(event - 1)
        device shouldBeDeviceOf hostPortBinding

        device
    }

    private def testHostPortMirrorDeleted(hostPortBindingId: UUID, obs: HostPortBindingObserver): Unit = {
        When("The host port binding is deleted")
        store.delete(classOf[TopologyHostPortBinding], hostPortBindingId)

        Then("The observer should receive a completed notification")
        obs.awaitCompletion(timeout)
        obs.getOnCompletedEvents should not be empty
    }

    feature("Host port binding mapper emits notifications for host port binding update") {
        scenario("The mapper emits error for non-existing host port binding") {
            Given("A host port binding identifier")
            val hostId = UUIDUtil.toProto(UUID.randomUUID())
            val portId = UUIDUtil.toProto(UUID.randomUUID())
            val hostPortBindingId = UUIDUtil.mix(hostId, portId)

            And("A host port binding mapper")
            val mapper = new HostPortBindingMapper(hostPortBindingId, vt)

            And("An observer to the host port binding mapper")
            val obs = createObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.awaitCompletion(timeout)
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyHostPortBinding]
            e.id shouldBe UUIDUtil.fromProto(hostPortBindingId)
        }

        scenario("The mapper emits existing host port binding") {
            val hostPortBindingId = UUID.randomUUID()
            val obs = createObserver()
            testHostPortBindingCreated(hostPortBindingId, obs)
        }

        scenario("The mapper emits new device on host port binding update") {
            val hostId = UUID.randomUUID()
            val portId = UUID.randomUUID()
            val hostPortBindingId = UUIDUtil.mix(hostId, portId)
            val obs = createObserver()
            testHostPortBindingCreated(UUIDUtil.fromProto(hostPortBindingId), obs)
            val hostPortBindingUpdate = createHostPortBinding(hostId, portId, "eth0")
            testHostPortBindingUpdated(hostPortBindingUpdate, obs, event = 2)
        }

        scenario("The mapper completes on host port binding delete") {
            val hostId = UUID.randomUUID()
            val portId = UUID.randomUUID()
            val hostPortBindingId = UUIDUtil.mix(hostId, portId)
            val obs = createObserver()
            testHostPortBindingCreated(hostPortBindingId, obs)
            testHostPortMirrorDeleted(hostPortBindingId, obs)
        }
    }
}
