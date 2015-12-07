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

package org.midonet.cluster.services.containers.schedulers

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{GivenWhenThen, Matchers, BeforeAndAfter, FlatSpec}
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.models.State.ContainerServiceStatus
import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class HostTrackerTest extends FlatSpec with SchedulersTest with BeforeAndAfter
                      with Matchers with GivenWhenThen {

    "Host tracker" should "complete for non-existent host" in {
        Given("A random host identifier")
        val hostId = UUID.randomUUID()

        And("A host tracker observer")
        val obs = new TestObserver[HostEvent]

        When("Creating a host tracker")
        val tracker = new HostTracker(hostId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a completed notification")
        obs.getOnCompletedEvents should have size 1

        And("The tracker should not be ready")
        tracker.isReady shouldBe false
        tracker.last shouldBe null
    }

    "Host tracker" should "emit existing host without state" in {
        Given("A host")
        val host = Host.newBuilder().setId(UUID.randomUUID.asProto).build()
        store.create(host)

        And("A host tracker observer")
        val obs = new TestObserver[HostEvent]

        When("Creating a host tracker")
        val tracker = new HostTracker(host.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostEvent(running = false)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe HostEvent(running = false)
    }

    "Host tracker" should "emit existing host with state" in {
        Given("A host")
        val host = Host.newBuilder().setId(UUID.randomUUID.asProto).build()
        val status = ContainerServiceStatus.newBuilder()
            .setWeight(random.nextInt()).build()
        store.create(host)
        store.addValueAs(host.getId.asJava.toString, classOf[Host], host.getId,
                         ContainerKey, status.toString).await()

        And("A host tracker observer")
        val obs = new TestObserver[HostEvent]

        When("Creating a host tracker")
        val tracker = new HostTracker(host.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostEvent(running = true, status)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe HostEvent(running = true, status)
    }

    "Host tracker" should "complete when host without state is deleted" in {
        Given("A host")
        val host = Host.newBuilder().setId(UUID.randomUUID.asProto).build()
        store.create(host)

        And("A host tracker observer")
        val obs = new TestObserver[HostEvent]

        When("Creating a host tracker")
        val tracker = new HostTracker(host.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostEvent(running = false)

        When("The host is deleted")
        store.delete(classOf[Host], host.getId)

        Then("The observer should receive a completed notification")
        obs.getOnCompletedEvents should have size 1

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe HostEvent(running = false)
    }

    "Host tracker" should "complete when host with state is deleted" in {
        Given("A host")
        val host = Host.newBuilder().setId(UUID.randomUUID.asProto).build()
        val status = ContainerServiceStatus.newBuilder()
                                           .setWeight(random.nextInt()).build()
        store.create(host)
        store.addValueAs(host.getId.asJava.toString, classOf[Host], host.getId,
                         ContainerKey, status.toString).await()

        And("A host tracker observer")
        val obs = new TestObserver[HostEvent]

        When("Creating a host tracker")
        val tracker = new HostTracker(host.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostEvent(running = true, status)

        When("The host is deleted")
        store.delete(classOf[Host], host.getId)

        Then("The observer should receive a completed notification")
        obs.getOnCompletedEvents should have size 1

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe HostEvent(running = true, status)
    }

    "Host tracker" should "emit notification when host state is added" in {
        Given("A host")
        val host = Host.newBuilder().setId(UUID.randomUUID.asProto).build()
        store.create(host)

        And("A host tracker observer")
        val obs = new TestObserver[HostEvent]

        When("Creating a host tracker")
        val tracker = new HostTracker(host.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostEvent(running = false)

        When("Adding a service status")
        val status = ContainerServiceStatus.newBuilder()
                                           .setWeight(random.nextInt()).build()
        store.addValueAs(host.getId.asJava.toString, classOf[Host], host.getId,
                         ContainerKey, status.toString).await()

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe HostEvent(running = true, status)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe HostEvent(running = true, status)
    }

    "Host tracker" should "emit notification when host state is updated" in {
        Given("A host")
        val host = Host.newBuilder().setId(UUID.randomUUID.asProto).build()
        val status1 = ContainerServiceStatus.newBuilder()
                                            .setWeight(random.nextInt()).build()
        store.create(host)
        store.addValueAs(host.getId.asJava.toString, classOf[Host], host.getId,
                         ContainerKey, status1.toString).await()

        And("A host tracker observer")
        val obs = new TestObserver[HostEvent]

        When("Creating a host tracker")
        val tracker = new HostTracker(host.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostEvent(running = true, status1)

        When("Updating the service status")
        val status2 = ContainerServiceStatus.newBuilder()
                                            .setWeight(random.nextInt()).build()
        store.addValueAs(host.getId.asJava.toString, classOf[Host], host.getId,
                         ContainerKey, status2.toString).await()

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe HostEvent(running = true, status2)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe HostEvent(running = true, status2)
    }

    "Host tracker" should "emit notification when host state is deleted" in {
        Given("A host")
        val host = Host.newBuilder().setId(UUID.randomUUID.asProto).build()
        val status = ContainerServiceStatus.newBuilder()
                                           .setWeight(random.nextInt()).build()
        store.create(host)
        store.addValueAs(host.getId.asJava.toString, classOf[Host], host.getId,
                         ContainerKey, status.toString).await()

        And("A host tracker observer")
        val obs = new TestObserver[HostEvent]

        When("Creating a host tracker")
        val tracker = new HostTracker(host.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostEvent(running = true, status)

        When("Deleting the service status")
        store.removeValueAs(host.getId.asJava.toString, classOf[Host],
                            host.getId, ContainerKey, value = null).await()

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe HostEvent(running = false)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe HostEvent(running = false)
    }

    "Host tracker" should "not emit duplicate states" in {
        Given("A host")
        val host = Host.newBuilder().setId(UUID.randomUUID.asProto).build()
        val status = ContainerServiceStatus.newBuilder()
                                           .setWeight(random.nextInt()).build()
        store.create(host)
        store.addValueAs(host.getId.asJava.toString, classOf[Host], host.getId,
                         ContainerKey, status.toString).await()

        And("A host tracker observer")
        val obs = new TestObserver[HostEvent]

        When("Creating a host tracker")
        val tracker = new HostTracker(host.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostEvent(running = true, status)

        When("Updating the service status with the same status")
        store.addValueAs(host.getId.asJava.toString, classOf[Host], host.getId,
                         ContainerKey, status.toString).await()

        Then("The observer should not receive a new notification")
        obs.getOnNextEvents should have size 1
    }

    "Host tracker" should "handle corrupted service status" in {
        Given("A host")
        val host = Host.newBuilder().setId(UUID.randomUUID.asProto).build()
        store.create(host)
        store.addValueAs(host.getId.asJava.toString, classOf[Host], host.getId,
                         ContainerKey, "corrupted status").await()

        And("A host tracker observer")
        val obs = new TestObserver[HostEvent]

        When("Creating a host tracker")
        val tracker = new HostTracker(host.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostEvent(running = false)
    }

    "Host tracker" should "handle observable error" in {
        Given("A host")
        val host = Host.newBuilder().setId(UUID.randomUUID.asProto).build()
        val status = ContainerServiceStatus.newBuilder()
                                           .setWeight(random.nextInt()).build()
        store.create(host)
        store.addValueAs(host.getId.asJava.toString, classOf[Host], host.getId,
                         ContainerKey, status.toString).await()

        And("A host tracker observer")
        val obs = new TestObserver[HostEvent]

        When("Creating a host tracker")
        val tracker = new HostTracker(host.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostEvent(running = true, status)

        When("The tracker store observable emits an error")
        store.keyObservableError(host.getId.asJava.toString, classOf[Host],
                                 host.getId, ContainerKey, new Throwable())

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe HostEvent(running = false)
        obs.getOnCompletedEvents should not be empty
    }

    "Host tracker" should "complete when complete method is called" in {
        Given("A host")
        val host = Host.newBuilder().setId(UUID.randomUUID.asProto).build()
        val status = ContainerServiceStatus.newBuilder()
            .setWeight(random.nextInt()).build()
        store.create(host)
        store.addValueAs(host.getId.asJava.toString, classOf[Host], host.getId,
                         ContainerKey, status.toString).await()

        And("A host tracker observer")
        val obs = new TestObserver[HostEvent]

        When("Creating a host tracker")
        val tracker = new HostTracker(host.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a host event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostEvent(running = true, status)

        When("Calling the complete method")
        tracker.complete()

        Then("The observer should receive a completed notification")
        obs.getOnCompletedEvents should have size 1
    }
}
