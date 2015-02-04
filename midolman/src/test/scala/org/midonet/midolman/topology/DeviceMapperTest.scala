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

import mockit.Mocked

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subscriptions.Subscriptions

import org.midonet.cluster.data.storage.StorageWithOwnership
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.functors._
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class DeviceMapperTest extends MidolmanSpec {

    class DeviceStream {
        val refCount = new AtomicInteger(0)
        val in = BehaviorSubject.create[TestableDevice]()
        val out = in
            .doOnSubscribe(makeAction0 { refCount.incrementAndGet() })
            .doOnUnsubscribe(makeAction0 { refCount.decrementAndGet() })
    }

    class TestableDevice(val id: UUID, val value: Int = 0) extends Device {
        override def equals(o: Any): Boolean = o match {
            case d: TestableDevice => (id eq d.id) && (value == d.value)
            case _ => false
        }
    }

    object TestableDevice {
        def apply(id: UUID, value: Int = 0) = new TestableDevice(id, value)
    }

    class TestableMapper(id: UUID, obs: Observable[TestableDevice])
                            (implicit vt: VirtualTopology)
            extends DeviceMapper[TestableDevice](id, vt) {

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

    @Mocked
    var storage: StorageWithOwnership = _
    implicit var vt: VirtualTopology = _

    override def beforeTest(): Unit = {
        vt = new VirtualTopology(storage, clusterDataClient, actorsService)
    }

    feature("Test device observable subscription") {
        scenario("An unused observable does not subscribe to storage") {
            Given("A storage stream")
            val stream = new DeviceStream()

            When("Creating a device observable for this stream")
            TestableObservable(UUID.randomUUID, stream.out)

            Then("The device observable should not be subscribed")
            stream.refCount.get should be (0)
        }

        scenario("A used observable subscribes to storage") {
            Given("A storage stream and an observer")
            val stream = new DeviceStream()
            val observer = new TestableObserver()

            When("Creating a device observable for this stream")
            val observable = TestableObservable(UUID.randomUUID, stream.out)

            Then("The device observable should not be subscribed")
            stream.refCount.get should be (0)

            When("An observer subscribes")
            observable.subscribe(observer)

            Then("The device observable should have one subscription")
            stream.refCount.get should be (1)
        }

        scenario("Multiple subscribers use the same subscription to storage") {
            Given("A storage stream and two observers")
            val stream = new DeviceStream()
            val observer1 = new TestableObserver()
            val observer2 = new TestableObserver()

            When("Creating a device observable for this stream")
            val observable = TestableObservable(UUID.randomUUID, stream.out)

            Then("The device mapper should not be subscribed")
            stream.refCount.get should be (0)

            When("An observer subscribes")
            observable.subscribe(observer1)

            Then("The device mapper should have one subscription")
            stream.refCount.get should be (1)

            When("A second observer subscribes")
            observable.subscribe(observer2)

            Then("The device mapper should have one subscription")
            stream.refCount.get should be (1)
        }

        scenario("Unsubscribing does not affect subscription to storage") {
            Given("A storage stream and two observers")
            val stream = new DeviceStream()
            val observer1 = new TestableObserver()
            val observer2 = new TestableObserver()

            When("Creating a device observable for this stream")
            val observable = TestableObservable(UUID.randomUUID, stream.out)

            Then("The device mapper should not be subscribed")
            stream.refCount.get should be (0)

            When("Both observers subscribes")
            val subscription1 = observable subscribe observer1
            val subscription2 = observable subscribe observer2

            Then("The device mapper should have one subscription")
            stream.refCount.get shouldBe 1

            When("Both observers unsubscribe")
            subscription1.unsubscribe()
            subscription2.unsubscribe()

            Then("The observers should be unsubscribed")
            subscription1.isUnsubscribed shouldBe true
            subscription2.isUnsubscribed shouldBe true

            And("The device observable should have one subscription")
            stream.refCount.get should be (1)
        }

        scenario("Stream completion notifies future subscribers") {
            Given("A storage stream and an observer")
            val stream = new DeviceStream()
            val observer = new TestableObserver()

            When("Creating a device observable for this stream")
            val observable = TestableObservable(UUID.randomUUID, stream.out)

            And("The stream completed")
            stream.in.onCompleted()

            And("The observer subscribes")
            val subscription = observable subscribe observer

            Then("The subscription should be the empty subscription")
            subscription should be (Subscriptions.unsubscribed())

            And("The observer should have received an IllegalStateException")
            observer.getOnNextEvents shouldBe empty
            observer.getOnCompletedEvents shouldBe empty
            observer.getOnErrorEvents should contain (DeviceMapper.SUBSCRIPTION_EXCEPTION)
        }

        scenario("Stream error notifies future subscribers") {
            Given("A storage stream and an observer")
            val stream = new DeviceStream()
            val observer = new TestableObserver()

            When("Creating a device observable for this stream")
            val observable = TestableObservable(UUID.randomUUID, stream.out)

            And("The stream emits an error")
            val e = new NullPointerException()
            stream.in.onError(e)

            And("The observer subscribes")
            val subscription = observable.subscribe(observer)

            Then("The subscription should be the empty subscription")
            subscription should be (Subscriptions.unsubscribed)

            And("The observer should have received the exception")
            observer.getOnErrorEvents should contain only (e)
            observer.getOnCompletedEvents shouldBe empty
            observer.getOnNextEvents shouldBe empty
        }
    }

    feature("Test observable updates the observers") {
        scenario("The observers receive the current state") {
            Given("A storage stream and two observers")
            val stream = new DeviceStream()
            val observer1 = new TestableObserver()
            val observer2 = new TestableObserver()

            When("Creating a device observable for this stream")
            val observable = TestableObservable(UUID.randomUUID, stream.out)

            And("And emitting an initial device")
            val id = UUID.randomUUID
            stream.in.onNext(TestableDevice(id, 0))

            And("The the first observer subscribes to the observable")
            observable.subscribe(observer1)

            Then("The first observer should see the device")
            observer1.getOnNextEvents should contain only (TestableDevice(id, 0))

            When("A second observer subscribes to the observable")
            observable.subscribe(observer2)

            Then("The second observer should see the device")
            observer2.getOnNextEvents should contain only (TestableDevice(id, 0))
        }

        scenario("The observer does not receive a device until created") {
            Given("A storage stream and two observers")
            val stream = new DeviceStream()
            val observer1 = new TestableObserver()
            val observer2 = new TestableObserver()

            When("Creating a device observable for this stream")
            val observable = TestableObservable(UUID.randomUUID, stream.out)

            And("The first observer subscribes to the observable")
            observable.subscribe(observer1)

            Then("The first observer should not receive an update")
            observer1.getOnNextEvents shouldBe empty

            When("The second observer subscribes to the observable")
            observable.subscribe(observer2)

            Then("The second observer should not receive an update")
            observer2.getOnNextEvents shouldBe empty

            When("The device is created")
            val id = UUID.randomUUID
            stream.in.onNext(TestableDevice(id, 0))

            Then("Both observers should see the device")
            observer1.getOnNextEvents should contain only TestableDevice(id, 0)
            observer2.getOnNextEvents should contain only TestableDevice(id, 0)

            When("The device is updated")
            stream.in.onNext(TestableDevice(id, 1))

            Then("Both observers should see the device")
            observer1.getOnNextEvents should contain inOrderOnly
                (TestableDevice(id, 0), TestableDevice(id, 1))
            observer2.getOnNextEvents should contain inOrderOnly
                (TestableDevice(id, 0), TestableDevice(id, 1))
        }

        scenario("The observer does not receive updates after unsubscribe") {
            Given("A storage stream and two observers")
            val stream = new DeviceStream()
            val observer1 = new TestableObserver()
            val observer2 = new TestableObserver()

            When("Creating a device observable for this stream")
            val observable = TestableObservable(UUID.randomUUID, stream.out)

            And("And emitting an initial device")
            val id = UUID.randomUUID
            stream.in.onNext(TestableDevice(id, 0))

            And("The both observers subscribe to the observable")
            val subscription1 = observable subscribe observer1
            val subscription2 = observable subscribe observer2

            Then("Both observers should see the device")
            observer1.getOnNextEvents should contain only TestableDevice(id, 0)
            observer2.getOnNextEvents should contain only TestableDevice(id, 0)

            When("The first observer unsubscribes")
            subscription1.unsubscribe()

            And("The device is updated")
            stream.in.onNext(TestableDevice(id, 1))

            Then("Only the second observer should see the update")
            observer1.getOnNextEvents should contain only TestableDevice(id, 0)
            observer2.getOnNextEvents should contain inOrderOnly
                (TestableDevice(id, 0), TestableDevice(id, 1))

            When("The second observer unsubscribes")
            subscription2.unsubscribe()

            And("The device is updates")
            stream.in.onNext(TestableDevice(id, 2))

            Then("No observer should see the update")
            observer1.getOnNextEvents should contain only TestableDevice(id, 0)
            observer2.getOnNextEvents should contain inOrderOnly
                (TestableDevice(id, 0), TestableDevice(id, 1))
        }

        scenario("Unsubscribing does not affect future subscribers") {
            Given("A storage stream and three observers")
            val stream = new DeviceStream()
            val observer1 = new TestableObserver()
            val observer2 = new TestableObserver()
            val observer3 = new TestableObserver()

            When("Creating a device observable for this stream")
            val observable = TestableObservable(UUID.randomUUID, stream.out)

            And("And emitting an initial device")
            val id = UUID.randomUUID
            stream.in.onNext(TestableDevice(id, 0))

            And("The first two observers subscribe to the observable")
            val subscription1 = observable.subscribe(observer1)
            val subscription2 = observable.subscribe(observer2)

            Then("Both observers should see the device")
            observer1.getOnNextEvents should contain only TestableDevice(id, 0)
            observer2.getOnNextEvents should contain only TestableDevice(id, 0)

            When("The both observers unsubscribes")
            subscription1.unsubscribe()
            subscription2.unsubscribe()

            And("The device is updated")
            stream.in.onNext(TestableDevice(id, 1))

            Then("No observer should see the device")
            observer1.getOnNextEvents should contain only TestableDevice(id, 0)
            observer2.getOnNextEvents should contain only TestableDevice(id, 0)

            When("The third observer subscribes to the observable")
            val subscription3 = observable.subscribe(observer3)

            Then("The third observer should see the update")
            observer3.getOnNextEvents should contain only TestableDevice(id, 1)

            When("The third observer unsubscribes")
            subscription3.unsubscribe()

            And("The device is updates")
            stream.in.onNext(TestableDevice(id, 2))

            Then("No observer should see the update")
            observer1.getOnNextEvents should contain only TestableDevice(id, 0)
            observer2.getOnNextEvents should contain only TestableDevice(id, 0)
            observer3.getOnNextEvents should contain only TestableDevice(id, 1)
        }

        scenario("Observers receive device deletion") {
            Given("A storage stream and an observer")
            val stream = new DeviceStream()
            val observer = new TestableObserver()

            When("Creating a device observable for this stream")
            val observable = TestableObservable(UUID.randomUUID, stream.out)

            And("And emitting an initial device")
            val id = UUID.randomUUID
            stream.in.onNext(TestableDevice(id, 0))

            And("The the observer subscribes to the observable")
            val subscription = observable.subscribe(observer)

            Then("The first observer should see the device")
            observer.getOnNextEvents should contain only TestableDevice(id, 0)

            When("The stream is completed")
            stream.in.onCompleted()

            Then("The observer should see the device deletion")
            observer.getOnNextEvents should contain only TestableDevice(id, 0)
            observer.getOnCompletedEvents should have size 1

            And("The observer should be unsubscribed")
            subscription.isUnsubscribed should be (true)
        }

        scenario("Observers receive device errors") {
            Given("A storage stream and an observer")
            val stream = new DeviceStream()
            val observer = new TestableObserver()

            When("Creating a device observable for this stream")
            val observable = TestableObservable(UUID.randomUUID, stream.out)

            And("And emitting an initial device")
            val id = UUID.randomUUID
            stream.in.onNext(TestableDevice(id, 0))

            And("The the observer subscribes to the observable")
            val subscription = observable.subscribe(observer)

            Then("The first observer should see the device")
            observer.getOnNextEvents should have size 1
            observer.getOnNextEvents should contain only TestableDevice(id, 0)

            When("The stream emits an error")
            val e = new NullPointerException()
            stream.in.onError(e)

            Then("The observer should see the device error")
            observer.getOnNextEvents should have size 1
            observer.getOnNextEvents.get(0) shouldBe TestableDevice(id, 0)
            observer.getOnErrorEvents.get(0) shouldBe e

            And("The observer should be unsubscribed")
            subscription.isUnsubscribed shouldBe true
        }
    }

    feature("Test observable as a future") {
        scenario("The future completes async on update") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = TestableObservable(id, stream.out)

            And("A future for this observable")
            val future = observable.asFuture

            Then("The future is not completed")
            future.isCompleted should be (false)

            When("The stream sends a device update")
            stream.in.onNext(TestableDevice(id, 0))

            Then("The future should have completed with the device")
            future.isCompleted should be (true)
            future.value should not be None
            future.value.get.isSuccess should be (true)
            future.value.get.get should be (TestableDevice(id, 0))
        }

        scenario("The future completes sync on update") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = TestableObservable(id, stream.out)

            When("The stream sends a device update")
            stream.in.onNext(TestableDevice(id, 0))

            And("A future for this observable")
            val future = observable.asFuture

            Then("The future should have completed with the device")
            future.isCompleted should be (true)
            future.value should not be None
            future.value.get.isSuccess should be (true)
            future.value.get.get should be (TestableDevice(id, 0))
        }

        scenario("The future completes async on completed") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = TestableObservable(id, stream.out)

            And("A future for this observable")
            val future = observable.asFuture

            Then("The future is not completed")
            future.isCompleted should be (false)

            When("The stream emits on completed")
            stream.in.onCompleted()

            Then("The future should have completed with an error")
            future.isCompleted should be (true)
            future.value should not be None
            future.value.get.isFailure should be (true)
            future.value.get.failed.get should be (RichObservable.COMPLETED_EXCEPTION)
        }

        scenario("The future completes sync on completed") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = TestableObservable(id, stream.out)

            When("The stream emits on completed")
            stream.in.onCompleted()

            And("A future for this observable")
            val future = observable.asFuture

            Then("The future should have completed with the device")
            future.isCompleted should be (true)
            future.value should not be None
            future.value.get.isFailure should be (true)
            future.value.get.failed.get should be (DeviceMapper.SUBSCRIPTION_EXCEPTION)
        }

        scenario("The future completes async on error") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = TestableObservable(id, stream.out)

            And("A future for this observable")
            val future = observable.asFuture

            Then("The future is not completed")
            future.isCompleted should be (false)

            When("The stream emits an error")
            val e = new NullPointerException()
            stream.in.onError(e)

            Then("The future should have completed with the error")
            future.isCompleted should be (true)
            future.value should not be None
            future.value.get.isFailure should be (true)
            future.value.get.failed.get should be (e)
        }

        scenario("The future completes sync on error") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = TestableObservable(id, stream.out)

            When("The stream emits an error")
            val e = new NullPointerException
            stream.in.onError(e)

            And("A future for this observable")
            val future = observable.asFuture

            Then("The future should have completed with the error")
            future.isCompleted should be (true)
            future.value should not be None
            future.value.get.isFailure should be (true)
            future.value.get.failed.get should be (e)
        }
    }

    feature("Test observable updates the topology cache") {
        scenario("The cache is not updated when there are no observers") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            TestableObservable(id, stream.out)

            Then("The virtual topology does not contain the device")
            vt.devices.containsKey(id) should be (false)

            When("The stream sends a device update")
            stream.in.onNext(TestableDevice(id, 0))

            Then("The virtual topology should not contain the device")
            vt.devices.containsKey(id) should be (false)
        }

        scenario("The cache contains the latest device") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = TestableObservable(id, stream.out)

            And("Any previous subscribed observer")
            observable.subscribe(new TestableObserver()).unsubscribe()

            Then("The virtual topology does not contain the device")
            vt.devices.containsKey(id) should be (false)

            When("The stream sends a device update")
            stream.in.onNext(TestableDevice(id, 0))

            Then("The virtual topology should contain the device")
            vt.devices.containsKey(id) should be (true)
            vt.devices.get(id) should be (TestableDevice(id, 0))

            When("The stream sends another device update")
            stream.in.onNext(TestableDevice(id, 1))

            Then("The virtual topology should contain the last version")
            vt.devices.containsKey(id) should be (true)
            vt.devices.get(id) should be (TestableDevice(id, 1))
        }

        scenario("The cache does not contains the device after an error") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = TestableObservable(id, stream.out)

            And("Any previous subscribed observer")
            observable.subscribe(new TestableObserver()).unsubscribe()

            Then("The virtual topology does not contain the device")
            vt.devices.containsKey(id) should be (false)

            When("The stream sends a device update")
            stream.in.onNext(TestableDevice(id, 0))

            Then("The virtual topology should contain the device")
            vt.devices.containsKey(id) should be (true)
            vt.devices.get(id) should be (TestableDevice(id, 0))

            When("The stream sends an error update")
            stream.in.onError(new NullPointerException())

            Then("The virtual topology does not contain the device")
            vt.devices.containsKey(id) should be (false)
        }

        scenario("The cache does not contain the device after deletion") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = TestableObservable(id, stream.out)

            And("Any previous subscribed observer")
            observable.subscribe(new TestableObserver()).unsubscribe()

            Then("The virtual topology does not contain the device")
            vt.devices.containsKey(id) should be (false)

            When("The stream sends a device update")
            stream.in.onNext(TestableDevice(id, 0))

            Then("The virtual topology should contain the device")
            vt.devices.containsKey(id) should be (true)
            vt.devices.get(id) should be (TestableDevice(id, 0))

            When("The stream sends a completed update")
            stream.in.onError(new NullPointerException())

            Then("The virtual topology does not contain the device")
            vt.devices.containsKey(id) should be (false)
        }
    }
}
