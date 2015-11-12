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
import rx.subjects.{PublishSubject, Subject}

import org.midonet.cluster.data.storage.{CreateOp, NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{Chain => TopologyChain}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.{TopologyMatchers, TopologyBuilder}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{Chain => SimulationChain}
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}
import org.midonet.util.reactivex.AwaitableObserver
import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class ObjectReferenceTrackerTest extends MidolmanSpec with TopologyBuilder
                                 with TopologyMatchers {

    import TopologyBuilder._

    class TestableDevice(val id: UUID, val chainIds: Set[UUID])
        extends VirtualDevice {
        override val deviceTag = FlowTagger.tagForBridge(id)
        var chains = Map.empty[UUID, SimulationChain]
        override def equals(o: Any): Boolean = o match {
            case d: TestableDevice => id == d.id && chainIds == d.chainIds
            case _ => false
        }
    }

    object TestableDevice {
        def apply(id: UUID, chainIds: UUID*) =
            new TestableDevice(id, chainIds.filter(_ ne null).toSet)
    }

    class TestableMapper(id: UUID, obs: Observable[TestableDevice])
                        (implicit vt: VirtualTopology)
        extends VirtualDeviceMapper[TestableDevice](id, vt) {

        private val chainsTracker = new ObjectReferenceTracker[SimulationChain](vt, log)

        @volatile private var device: TestableDevice = null
        private lazy val deviceObservable = obs
            .observeOn(vt.vtScheduler)
            .doOnCompleted(makeAction0(deviceDeleted()))
            .doOnNext(makeAction1(deviceUpdated))
        protected lazy val observable = Observable
            .merge(chainsTracker.refsObservable.map[TestableDevice](makeFunc1(_ =>
                    TestableDevice(device.id, chainsTracker.currentRefs.keySet.toSeq: _*))),
                   deviceObservable)
            .filter(makeFunc1(isDeviceReady))

        private def deviceDeleted(): Unit = chainsTracker.completeRefs()

        private def deviceUpdated(device: TestableDevice): Unit = {
            this.device = TestableDevice(device.id,
                                         chainsTracker.currentRefs.keySet.toSeq: _*)
            chainsTracker.requestRefs(device.chainIds)
        }

        private def isDeviceReady(device: TestableDevice): Boolean = {
            val ready = chainsTracker.areRefsReady
            if (ready) device.chains = chainsTracker.currentRefs
            ready
        }
    }

    object TestableObservable {
        def apply(id: UUID) = Observable.create(new TestableMapper(id, stream))
    }

    private implicit var vt: VirtualTopology = _
    private var store: Storage = _
    private var stream: Subject[TestableDevice, TestableDevice] = _
    private final val timeout = 5 seconds

    override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        stream = PublishSubject.create[TestableDevice]
    }

    private def createObserver() =
        new TestObserver[TestableDevice] with AwaitableObserver[TestableDevice]

    feature("The device with chain mapper fetches chains") {
        scenario("The device chain does not exist") {
            Given("A device observable")
            val id = UUID.randomUUID
            val observable = TestableObservable(id)

            And("A device observer subscribed to the observable")
            val observer = createObserver()
            observable.subscribe(observer)

            When("The stream emits a device with chains")
            val chainId = UUID.randomUUID
            stream.onNext(TestableDevice(id, chainId))

            Then("The observer should receive an error")
            observer.awaitCompletion(timeout)
            val e = observer.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyChain]
            e.id shouldBe chainId
        }

        scenario("The device does not have chains") {
            Given("A device observable")
            val id = UUID.randomUUID
            val observable = TestableObservable(id)

            And("A device observer subscribed to the observable")
            val observer = createObserver()
            observable.subscribe(observer)

            When("The stream emits a device without chains")
            val device = TestableDevice(id)
            stream.onNext(device)

            Then("The observer should receive the device")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBe device
        }

        scenario("The device receives existing chain") {
            Given("A device observable")
            val id = UUID.randomUUID
            val observable = TestableObservable(id)

            And("A device observer subscribed to the observable")
            val observer = createObserver()
            observable.subscribe(observer)

            And("A chain")
            val chain = createChain()
            store.create(chain)

            When("The stream emits a device with chains")
            stream.onNext(TestableDevice(id, chain.getId))

            Then("The observer should receive the device")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBe TestableDevice(id, chain.getId)
            observer.getOnNextEvents.get(0).chains(chain.getId) shouldBeDeviceOf chain
        }

        scenario("The device waits for multiple chains") {
            Given("A device observable")
            val id = UUID.randomUUID
            val observable = TestableObservable(id)

            And("A device observer subscribed to the observable")
            val observer = createObserver()
            observable.subscribe(observer)

            And("Three chains")
            val chain1 = createChain()
            val chain2 = createChain()
            val chain3 = createChain()
            store.multi(Seq(CreateOp(chain1), CreateOp(chain2), CreateOp(chain3)))

            When("The stream emits a device with chains")
            stream.onNext(TestableDevice(id, chain1.getId, chain2.getId,
                                         chain3.getId))

            Then("The observer should receive the device")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBe TestableDevice(
                id, chain1.getId, chain2.getId, chain3.getId)
            observer.getOnNextEvents.get(0).chains(chain1.getId) shouldBeDeviceOf chain1
            observer.getOnNextEvents.get(0).chains(chain2.getId) shouldBeDeviceOf chain2
            observer.getOnNextEvents.get(0).chains(chain3.getId) shouldBeDeviceOf chain3
        }

        scenario("The device updates when adding chain") {
            Given("A device observable")
            val id = UUID.randomUUID
            val observable = TestableObservable(id)

            And("A device observer subscribed to the observable")
            val observer = createObserver()
            observable.subscribe(observer)

            And("A chain")
            val chain = createChain()
            store.create(chain)

            When("The stream emits the device")
            stream.onNext(TestableDevice(id))

            Then("The observer should receive the device")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBe TestableDevice(id)

            When("Add the chain to the device")
            stream.onNext(TestableDevice(id, chain.getId))

            Then("The observer should receive the device with the chain")
            observer.awaitOnNext(2, timeout)
            observer.getOnNextEvents.get(1) shouldBe TestableDevice(id, chain.getId)
            observer.getOnNextEvents.get(1).chains(chain.getId) shouldBeDeviceOf chain
        }

        scenario("The device updates when updating chain") {
            Given("A device observable")
            val id = UUID.randomUUID
            val observable = TestableObservable(id)

            And("A device observer subscribed to the observable")
            val observer = createObserver()
            observable.subscribe(observer)

            And("A chain")
            val chain1 = createChain()
            store.create(chain1)

            When("The stream emits a device with chains")
            stream.onNext(TestableDevice(id, chain1.getId))

            Then("The observer should receive the device")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBe TestableDevice(id, chain1.getId)

            When("Updating the chain")
            val chain2 = chain1.setName("Updated chain")
            store.update(chain2)

            Then("The observer should receive the updated device")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents.get(1) shouldBe TestableDevice(id, chain2.getId)
            observer.getOnNextEvents.get(1).chains(chain2.getId) shouldBeDeviceOf chain2
        }

        scenario("The device updates when removing chain") {
            Given("A device observable")
            val id = UUID.randomUUID
            val observable = TestableObservable(id)

            And("A device observer subscribed to the observable")
            val observer = createObserver()
            observable.subscribe(observer)

            And("A chain")
            val chain = createChain()
            store.create(chain)

            When("The stream emits a device with chains")
            stream.onNext(TestableDevice(id, chain.getId))

            Then("The observer should receive the device")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBe TestableDevice(id, chain.getId)

            When("Removing the chain from the device")
            stream.onNext(TestableDevice(id))

            Then("The observer should receive the device without the chain")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents.get(1) shouldBe TestableDevice(id)
            observer.getOnNextEvents.get(1).chains shouldBe empty
        }

        scenario("The observer receives on complete when device is deleted") {
            Given("A device observable")
            val id = UUID.randomUUID
            val observable = TestableObservable(id)

            And("A device observer subscribed to the observable")
            val observer = createObserver()
            observable.subscribe(observer)

            And("Three chains")
            val chain1 = createChain()
            val chain2 = createChain()
            val chain3 = createChain()
            store.multi(Seq(CreateOp(chain1), CreateOp(chain2), CreateOp(chain3)))

            When("The stream emits a device with chains")
            stream.onNext(TestableDevice(id, chain1.getId, chain2.getId,
                                         chain3.getId))

            Then("The observer should receive the device")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBe TestableDevice(
                id, chain1.getId, chain2.getId, chain3.getId)

            When("The stream emits on completed")
            stream.onCompleted()

            Then("The observable should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should not be empty
        }

        scenario("Mapper does not emit device until all chains are loaded") {
            Given("A device observable")
            val id = UUID.randomUUID
            val observable = TestableObservable(id)

            And("Three chains")
            val chain1 = createChain()
            val chain2 = createChain()
            val chain3 = createChain()
            store.multi(Seq(CreateOp(chain1), CreateOp(chain2), CreateOp(chain3)))

            When("Requesting the chains to have them cached")
            VirtualTopology.get[SimulationChain](chain1.getId)
                           .await(timeout) shouldBeDeviceOf chain1
            VirtualTopology.get[SimulationChain](chain2.getId)
                           .await(timeout) shouldBeDeviceOf chain2
            VirtualTopology.get[SimulationChain](chain3.getId)
                           .await(timeout) shouldBeDeviceOf chain3

            And("A device observer subscribed to the observable")
            val observer = createObserver()
            observable.subscribe(observer)

            And("The stream emits a device with chains")
            stream.onNext(TestableDevice(id, chain1.getId, chain2.getId,
                                         chain3.getId))

            Then("The observer should receive the device")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBe TestableDevice(
                id, chain1.getId, chain2.getId, chain3.getId)
        }
    }
}
