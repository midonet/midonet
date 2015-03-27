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
package org.midonet.midolman.topology

import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.Observable
import rx.subjects.BehaviorSubject

import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.sdn.flows.FlowTagger.DeviceTag
import org.midonet.util.functors._
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class VirtualDeviceMapperTest extends MidolmanSpec {

    class DeviceStream {
        val refCount = new AtomicInteger(0)
        val in = BehaviorSubject.create[TestableDevice]()
        val out = in
            .doOnSubscribe(makeAction0 { refCount.incrementAndGet() })
            .doOnUnsubscribe(makeAction0 { refCount.decrementAndGet() })
    }

    class TestableDevice(val id: UUID, val value: Int = 0) extends VirtualDevice {
        override def equals(o: Any): Boolean = o match {
            case d: TestableDevice => (id eq d.id) && (value == d.value)
            case _ => false
        }
        override def deviceTag = DeviceTag(id)
    }

    object TestableDevice {
        def apply(id: UUID, value: Int = 0) = new TestableDevice(id, value)
    }

    class TestableMapper(id: UUID, obs: Observable[TestableDevice])
                        (implicit vt: VirtualTopology)
        extends VirtualDeviceMapper[TestableDevice](id, vt) {

        private val subscribed = new AtomicBoolean(false)
        private val stream = BehaviorSubject.create[TestableDevice]()

        protected override def observable = {
            if (subscribed.compareAndSet(false, true)) {
                obs.subscribe(stream)
            }
            stream
        }
    }

    object TestableObservable {
        def apply(id: UUID, obs: Observable[TestableDevice]) =
            Observable.create(new TestableMapper(id, obs))
    }

    type TestableObserver = AwaitableObserver[TestableDevice]

    private implicit var vt: VirtualTopology = _

    override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
    }

    feature("Test the flows tags are invalidated") {
        scenario("The flow tags are not invalidated for no observers") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            TestableObservable(id, stream.out)

            When("The stream sends a first device update")
            stream.in.onNext(TestableDevice(id, 0))

            Then("The flow controller should not received any message")
            flowInvalidator.get() should be (empty)

            When("The stream sends a second device update")
            stream.in.onNext(TestableDevice(id, 1))

            Then("The flow controller should not received any message")
            flowInvalidator.get() should be (empty)
        }

        scenario("The flow tags are invalidated for a device update") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = TestableObservable(id, stream.out)

            And("Any previous subscribed observer")
            observable.subscribe(new TestableObserver()).unsubscribe()

            When("The stream sends a first device update")
            stream.in.onNext(TestableDevice(id, 0))

            Then("The flow controller received one invalidation message")
            flowInvalidator.get() should contain only DeviceTag(id)

            When("The stream sends a second device update")
            stream.in.onNext(TestableDevice(id, 1))

            Then("The flow controller received two invalidation messages")
            flowInvalidator.get() should contain theSameElementsInOrderAs Vector(
                DeviceTag(id), DeviceTag(id))
        }

        scenario("The flow tags are invalidated on device error") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = TestableObservable(id, stream.out)

            And("Any previous subscribed observer")
            observable.subscribe(new TestableObserver()).unsubscribe()

            When("The stream sends a device update")
            stream.in.onNext(TestableDevice(id, 0))

            Then("The flow controller received one invalidation message")
            flowInvalidator.get() should contain only DeviceTag(id)

            When("The stream sends an error update")
            stream.in.onError(new NullPointerException())

            Then("The flow controller received two invalidation messages")
            flowInvalidator.get() should contain theSameElementsInOrderAs Vector(
                DeviceTag(id), DeviceTag(id))
        }

        scenario("The flow tags are invalidated on device deletion") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = TestableObservable(id, stream.out)

            And("Any previous subscribed observer")
            observable.subscribe(new TestableObserver()).unsubscribe()

            When("The stream sends a device update")
            stream.in.onNext(TestableDevice(id, 0))

            Then("The flow controller received one invalidation message")
            flowInvalidator.get() should contain only DeviceTag(id)

            When("The stream sends a completed update")
            stream.in.onError(new NullPointerException())

            Then("The flow controller received two invalidation messages")
            flowInvalidator.get() should contain theSameElementsInOrderAs Vector(
                DeviceTag(id), DeviceTag(id))
        }
    }
}
