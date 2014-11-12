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
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.FlowController
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.sdn.flows.FlowTagger.DeviceTag

import mockit.Mocked

import rx.{Observer, Observable}
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage.Storage
import org.midonet.util.functors._

@RunWith(classOf[JUnitRunner])
class DeviceObservableTest extends MidolmanSpec {

    class Update
    case class Completed() extends Update
    case class Next(device: TestableDevice) extends Update
    case class Error(e: Throwable) extends Update

    class DeviceStream {

        val refCount = new AtomicInteger(0)

        val in = PublishSubject.create[TestableDevice]()
        val out = in
            .doOnSubscribe(makeAction0 { refCount.incrementAndGet() })
            .doOnUnsubscribe(makeAction0 { refCount.decrementAndGet() })
    }

    class TestableDevice(val id: UUID, val value: Int = 0) extends Device {
        override def equals(o: Any): Boolean = o match {
            case d: TestableDevice => (id eq d.id) && (value == d.value)
            case _ => false
        }
        override def deviceTag = DeviceTag(id)
    }

    class TestableObservable(id: UUID, obs: Observable[TestableDevice])
                            (implicit vt: VirtualTopology)
            extends DeviceObservable[TestableDevice](id, vt) {

        protected override def observable = obs
    }

    class TestableObserver extends Observer[TestableDevice] {

        val events = new mutable.MutableList[Update]()

        override def onCompleted(): Unit = {
            events += Completed()
        }
        override def onError(e: Throwable): Unit = {
            events += Error(e)
        }
        override def onNext(device: TestableDevice): Unit = {
            events += Next(device)
        }
    }

    @Mocked
    var storage: Storage = _
    implicit var vt: VirtualTopology = _

    registerActors(FlowController -> (() => new FlowController
                                                with MessageAccumulator))

    def fc = FlowController.as[FlowController with MessageAccumulator]

    override def beforeTest(): Unit = {
        vt = new VirtualTopology(storage, actorsService)
    }

    feature("Test device observable subscription") {
        scenario("Test the observable subscribes and subsubscribes") {
            Given("A storage stream")
            val stream = new DeviceStream()

            When("Creating a device observable for this stream")
            val observable = new TestableObservable(UUID.randomUUID, stream.out)

            Then("The device observable should be subscribed")
            stream.refCount.get should be (1)

            When("The device observable unsubscribes unsafely")
            observable.unsafeUnsubscribe()

            Then("The device observable should be unsubscribed")
            stream.refCount.get should be (0)
        }

        scenario("Test the observable unsubscribes on updates completion") {
            Given("A storage stream")
            val stream = new DeviceStream()

            When("Creating a device observable for this stream")
            val observable = new TestableObservable(UUID.randomUUID, stream.out)

            Then("The device observable should be subscribed")
            stream.refCount.get should be (1)

            When("The update stream completes")
            stream.in.onCompleted()

            Then("The device observable should be unsubscribed")
            stream.refCount.get should be (0)
        }
    }

    feature("Test not subscribed observable") {
        scenario("Test getEventually() fails") {
            Given("A device observable connected to a storage stream")
            val stream = new DeviceStream()
            val observable = new TestableObservable(UUID.randomUUID, stream.out)

            When("The observable is unsubscribed unsafely")
            observable.unsafeUnsubscribe()

            Then("The getEventually() method should fail")
            intercept[IllegalStateException] {
                observable.getEventually
            }
        }

        scenario("Test subscribe() fails") {
            Given("A device observable connected to a storage stream")
            val stream = new DeviceStream()
            val observable = new TestableObservable(UUID.randomUUID, stream.out)
            val observer = new TestableObserver()

            When("The observable is unsubscribed unsafely")
            observable.unsafeUnsubscribe()

            Then("The subscribe() method should fail")
            intercept[IllegalStateException] {
                observable.subscribe(observer)
            }
        }
    }

    feature("Test observable updates with getEventually()") {
        scenario("Test observable completes a future async for onNext") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            When("Calling getEventually()")
            val future = observable.getEventually

            Then("The future should not be completed")
            future.isCompleted should be (false)

            When("The stream sends a device update")
            stream.in.onNext(new TestableDevice(id))

            Then("The future should be completed")
            future.isCompleted should be (true)

            And("The future should contain the device")
            future.value should not be None
            future.value.get.isSuccess should be (true)
            future.value.get.get should not be null
            future.value.get.get.id should be (id)
            future.value.get.get.value should be (0)
        }

        scenario("Test observable completes a future sync for onNext") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            When("The stream sends a device update")
            stream.in.onNext(new TestableDevice(id))

            And("Calling getEventually()")
            val future = observable.getEventually

            Then("The future should be completed")
            future.isCompleted should be (true)

            And("The future should contain the device")
            future.value should not be None
            future.value.get.isSuccess should be (true)
            future.value.get.get should not be null
            future.value.get.get.id should be (id)
            future.value.get.get.value should be (0)
        }

        scenario("Test observable completes a future sync for last onNext") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            When("The stream sends two device updates")
            stream.in.onNext(new TestableDevice(id, 0))
            stream.in.onNext(new TestableDevice(id, 1))

            And("Calling getEventually()")
            val future = observable.getEventually

            Then("The future should be completed")
            future.isCompleted should be (true)

            And("The future should contain the last device")
            future.value should not be None
            future.value.get.isSuccess should be (true)
            future.value.get.get should not be null
            future.value.get.get.id should be (id)
            future.value.get.get.value should be (1)
        }

        scenario("Test observable completes a future async for onCompleted") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            When("Calling getEventually()")
            val future = observable.getEventually

            Then("The future should not be completed")
            future.isCompleted should be (false)

            When("The stream sends a completion update")
            stream.in.onCompleted()

            Then("The future should be completed")
            future.isCompleted should be (true)

            And("The future should fail with an IllegalStateException")
            future.value should not be None
            future.value.get.isFailure should be (true)
            future.value.get.failed.get.getClass should be (classOf[IllegalStateException])
        }

        scenario("Test observable throws sync for onCompleted") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            When("The stream sends a completion update")
            stream.in.onCompleted()

            Then("Calling getEventually() should throw an exception")
            intercept[IllegalStateException] {
                observable.getEventually
            }
        }

        scenario("Test observable completes a future async for onError") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            When("Calling getEventually()")
            val future = observable.getEventually

            Then("The future should not be completed")
            future.isCompleted should be (false)

            When("The stream sends an error update")
            val e = new NullPointerException()
            stream.in.onError(e)

            Then("The future should be completed")
            future.isCompleted should be (true)

            And("The future should fail with the error exception")
            future.value should not be None
            future.value.get.isFailure should be (true)
            future.value.get.failed.get should be (e)
        }

        scenario("Test observable completes a future sync for onError") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            When("The stream sends a completion update")
            val e = new NullPointerException
            stream.in.onError(e)

            And("Calling getEventually()")
            val future = observable.getEventually

            Then("The future should be completed")
            future.isCompleted should be (true)

            And("The future should fail with the error exception")
            future.value should not be None
            future.value.get.isFailure should be (true)
            future.value.get.failed.get should be (e)
        }
    }

    feature("Test observable updates with subscribe()") {
        scenario("Test subscription lifecycle") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            And("An observer")
            val observer = new TestableObserver()

            When("The observer subscribes")
            val subscription = observable.subscribe(observer)

            Then("The observer should be subscribed")
            subscription.isUnsubscribed should be (false)

            When("The observer unsubscribes")
            subscription.unsubscribe()

            Then("The observer should be unsubscribed")
            subscription.isUnsubscribed should be (true)
        }

        scenario("Test subscriber receives updates after subscription") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            And("An observer")
            val observer = new TestableObserver()

            When("The observer subscribes")
            observable.subscribe(observer)

            Then("The observer should not have received any events")
            observer.events.isEmpty should be (true)

            When("The stream sends two device updates")
            stream.in.onNext(new TestableDevice(id, 0))
            stream.in.onNext(new TestableDevice(id, 1))

            Then("The observer should see these devices in order")
            observer.events should contain inOrder(
                Next(new TestableDevice(id, 0)),
                Next(new TestableDevice(id, 1)))
        }

        scenario("Test subscriber receives current update after subscription") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            And("An observer")
            val observer = new TestableObserver()

            When("The stream sends a device update")
            stream.in.onNext(new TestableDevice(id, 0))

            And("The observer subscribes")
            observable.subscribe(observer)

            Then("The observer should see the current device")
            observer.events should contain only Next(new TestableDevice(id, 0))

            When("The stream sends two device updates")
            stream.in.onNext(new TestableDevice(id, 1))
            stream.in.onNext(new TestableDevice(id, 2))

            Then("The observer should see these devices in order")
            observer.events should contain inOrder(
                Next(new TestableDevice(id, 0)),
                Next(new TestableDevice(id, 1)),
                Next(new TestableDevice(id, 2)))
        }

        scenario("Test subscriber receives onCompleted and unsubscribes") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            And("An observer")
            val observer = new TestableObserver()

            When("The observer subscribes")
            val subscription = observable.subscribe(observer)

            Then("The observer should not have received any events")
            observer.events.isEmpty should be (true)

            When("The stream sends a completed update")
            stream.in.onNext(new TestableDevice(id, 0))
            stream.in.onCompleted()

            Then("The observer should see these devices in order")
            observer.events should contain inOrder(
                Next(new TestableDevice(id, 0)), Completed())

            And("The observer should be unsubscribed")
            subscription.isUnsubscribed should be (true)
        }

        scenario("Test subscriber receives onError and unsubscribes") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            And("An observer")
            val observer = new TestableObserver()

            When("The observer subscribes")
            val subscription = observable.subscribe(observer)

            Then("The observer should not have received any events")
            observer.events.isEmpty should be (true)

            When("The stream sends an error update")
            val e = new NullPointerException()
            stream.in.onNext(new TestableDevice(id, 0))
            stream.in.onError(e)

            Then("The observer should see these devices in order")
            observer.events should contain inOrder(
                Next(new TestableDevice(id, 0)), Error(e))

            And("The observer should be unsubscribed")
            subscription.isUnsubscribed should be (true)
        }

        scenario("Test subscriber throws after onComplete") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            And("An observer")
            val observer = new TestableObserver()

            When("The stream sends a completed update")
            stream.in.onCompleted()

            And("The observer subscribing should throw an exception")
            intercept[IllegalStateException] {
                observable.subscribe(observer)
            }
        }

        scenario("Test subscriber throws after onError") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            And("An observer")
            val observer = new TestableObserver()

            When("The stream sends an error update")
            stream.in.onError(new NullPointerException())

            And("The observer subscribing should throw an exception")
            intercept[NullPointerException] {
                observable.subscribe(observer)
            }
        }
    }

    feature("Test observable updates the topology cache") {
        scenario("The cache contains the latest device") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            Then("The virtual topology does not contain the device")
            vt.devices.containsKey(id) should be (false)

            When("The stream sends a device update")
            stream.in.onNext(new TestableDevice(id, 0))

            Then("The virtual topology should contain the device")
            vt.devices.containsKey(id) should be (true)
            vt.devices.get(id) should be (new TestableDevice(id, 0))

            When("The stream sends another device update")
            stream.in.onNext(new TestableDevice(id, 1))

            Then("The virtual topology should contain the last version")
            vt.devices.containsKey(id) should be (true)
            vt.devices.get(id) should be (new TestableDevice(id, 1))
        }

        scenario("The cache does not contains the device after an error") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            Then("The virtual topology does not contain the device")
            vt.devices.containsKey(id) should be (false)

            When("The stream sends a device update")
            stream.in.onNext(new TestableDevice(id, 0))

            Then("The virtual topology should contain the device")
            vt.devices.containsKey(id) should be (true)
            vt.devices.get(id) should be (new TestableDevice(id, 0))

            When("The stream sends an error update")
            stream.in.onError(new NullPointerException())

            Then("The virtual topology does not contain the device")
            vt.devices.containsKey(id) should be (false)
        }

        scenario("The cache does not contain the device after deletion") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            Then("The virtual topology does not contain the device")
            vt.devices.containsKey(id) should be (false)

            When("The stream sends a device update")
            stream.in.onNext(new TestableDevice(id, 0))

            Then("The virtual topology should contain the device")
            vt.devices.containsKey(id) should be (true)
            vt.devices.get(id) should be (new TestableDevice(id, 0))

            When("The stream sends a completed update")
            stream.in.onError(new NullPointerException())

            Then("The virtual topology does not contain the device")
            vt.devices.containsKey(id) should be (false)
        }
    }

    feature("Test the flows tags are invalidated") {
        scenario("The flow tags are invalidated for a device update") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            When("The stream sends a first device update")
            stream.in.onNext(new TestableDevice(id, 0))

            Then("The flow controller received one invalidation message")
            fc.messages should contain only InvalidateFlowsByTag(DeviceTag(id))

            When("The stream sends a second device update")
            stream.in.onNext(new TestableDevice(id, 1))

            Then("The flow controller received two invalidation messages")
            fc.messages should contain theSameElementsInOrderAs Vector(
                InvalidateFlowsByTag(DeviceTag(id)),
                InvalidateFlowsByTag(DeviceTag(id)))
        }

        scenario("The flow tags are invalidated on device error") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            When("The stream sends a device update")
            stream.in.onNext(new TestableDevice(id, 0))

            Then("The flow controller received one invalidation message")
            fc.messages should contain only InvalidateFlowsByTag(DeviceTag(id))

            When("The stream sends an error update")
            stream.in.onError(new NullPointerException())

            Then("The flow controller received two invalidation messages")
            fc.messages should contain theSameElementsInOrderAs Vector(
                InvalidateFlowsByTag(DeviceTag(id)),
                InvalidateFlowsByTag(DeviceTag(id)))
        }

        scenario("The flow tags are invalidated on device deletion") {
            Given("A device observable connected to a storage stream")
            val id = UUID.randomUUID
            val stream = new DeviceStream()
            val observable = new TestableObservable(id, stream.out)

            When("The stream sends a device update")
            stream.in.onNext(new TestableDevice(id, 0))

            Then("The flow controller received one invalidation message")
            fc.messages should contain only InvalidateFlowsByTag(DeviceTag(id))

            When("The stream sends a completed update")
            stream.in.onError(new NullPointerException())

            Then("The flow controller received two invalidation messages")
            fc.messages should contain theSameElementsInOrderAs Vector(
                InvalidateFlowsByTag(DeviceTag(id)),
                InvalidateFlowsByTag(DeviceTag(id)))
        }
    }
}
