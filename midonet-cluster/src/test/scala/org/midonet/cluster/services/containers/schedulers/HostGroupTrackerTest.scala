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
import org.scalatest.{Matchers, GivenWhenThen, BeforeAndAfter, FlatSpec}
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.models.Topology.{Host, HostGroup}
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class HostGroupTrackerTest extends FlatSpec with SchedulersTest with BeforeAndAfter
                           with Matchers with GivenWhenThen {

    "Host group tracker" should "receive an error for non-existent group" in {
        Given("A random host group identifier")
        val hostGroupId = UUID.randomUUID()

        And("A host group tracker observer")
        val obs = new TestObserver[HostGroupEvent]

        When("Creating a host group tracker")
        val tracker = new HostGroupTracker(hostGroupId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive an error")
        obs.getOnErrorEvents should have size 1
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[NotFoundException]

        And("The tracker should not be ready")
        tracker.isReady shouldBe false
        tracker.last shouldBe null
    }

    "Host group tracker" should "emit group with no members" in {
        Given("A host group")
        val hostGroup = createHostGroup()

        And("A host group tracker observer")
        val obs = new TestObserver[HostGroupEvent]

        When("Creating a host group tracker")
        val tracker = new HostGroupTracker(hostGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostGroupEvent(hostGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe HostGroupEvent(hostGroup.getId, Map())
    }

    "Host group tracker" should "emit host member without state" in {
        Given("A host in a host group")
        val host1 = createHost()
        val hostGroup = createHostGroup(host1.getId)

        And("A host group tracker observer")
        val obs = new TestObserver[HostGroupEvent]

        When("Creating a host group tracker")
        val tracker = new HostGroupTracker(hostGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostGroupEvent(
            hostGroup.getId,
            Map(host1.getId.asJava -> HostEvent(running = false)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe HostGroupEvent(
            hostGroup.getId,
            Map(host1.getId.asJava -> HostEvent(running = false)))
    }

    "Host group state" should "emit host member with state" in {
        Given("A host in a host group")
        val host1 = createHost()
        val hostGroup = createHostGroup(host1.getId)
        val status1 = createHostStatus(host1.getId)

        And("A host group tracker observer")
        val obs = new TestObserver[HostGroupEvent]

        When("Creating a host group tracker")
        val tracker = new HostGroupTracker(hostGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostGroupEvent(
            hostGroup.getId,
            Map(host1.getId.asJava -> HostEvent(running = true, status1)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe HostGroupEvent(
            hostGroup.getId,
            Map(host1.getId.asJava -> HostEvent(running = true, status1)))
    }

    "Host group state" should "emit when a host is added to the group" in {
        Given("A host group")
        val hostGroup1 = createHostGroup()

        And("A host group tracker observer")
        val obs = new TestObserver[HostGroupEvent]

        When("Creating a host group tracker")
        val tracker = new HostGroupTracker(hostGroup1.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostGroupEvent(hostGroup1.getId, Map())

        When("Adding a host to host group")
        val host1 = createHost()
        val hostGroup2 = hostGroup1.toBuilder.addHostIds(host1.getId).build()
        store update hostGroup2

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe HostGroupEvent(
            hostGroup2.getId,
            Map(host1.getId.asJava -> HostEvent(running = false)))
    }

    "Host group state" should "emit when a host is removed from the group" in {
        Given("A host in a host group")
        val host1 = createHost()
        val hostGroup1 = createHostGroup(host1.getId)

        And("A host group tracker observer")
        val obs = new TestObserver[HostGroupEvent]

        When("Creating a host group tracker")
        val tracker = new HostGroupTracker(hostGroup1.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe HostGroupEvent(
            hostGroup1.getId,
            Map(host1.getId.asJava -> HostEvent(running = false)))

        When("The host is removed from the group")
        val hostGroup2 = hostGroup1.toBuilder.clearHostIds().build()
        store update hostGroup2

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe HostGroupEvent(hostGroup2.getId, Map())
    }

    "Host group state" should "complete when host group is deleted" in {
        Given("A host in a host group")
        val host1 = createHost()
        val hostGroup = createHostGroup(host1.getId)

        And("A host group tracker observer")
        val obs = new TestObserver[HostGroupEvent]

        When("Creating a host group tracker")
        val tracker = new HostGroupTracker(hostGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The host group is deleted")
        store.delete(classOf[HostGroup], hostGroup.getId)

        Then("The observer should receive a completed notification")
        obs.getOnCompletedEvents should have size 1
    }

    "Host group state" should "complete when complete method is called" in {
        Given("A host in a host group")
        val host1 = createHost()
        val hostGroup = createHostGroup(host1.getId)

        And("A host group tracker observer")
        val obs = new TestObserver[HostGroupEvent]

        When("Creating a host group tracker")
        val tracker = new HostGroupTracker(hostGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("Calling the complete method")
        tracker.complete()

        Then("The observer should receive a completed notification")
        obs.getOnCompletedEvents should have size 1
    }

    "Host group state" should "remove deleted host members" in {
        Given("A host in a host group")
        val host1 = createHost()
        val hostGroup = createHostGroup(host1.getId)

        And("A host group tracker observer")
        val obs = new TestObserver[HostGroupEvent]

        When("Creating a host group tracker")
        val tracker = new HostGroupTracker(hostGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The host emits an error")
        store.delete(classOf[Host], host1.getId)

        Then("The observer should receive a notification with the host removed")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe HostGroupEvent(hostGroup.getId, Map())
    }

    "Host group state" should "remove host members that emit errors" in {
        Given("A host in a host group")
        val host1 = createHost()
        val hostGroup = createHostGroup(host1.getId)

        And("A host group tracker observer")
        val obs = new TestObserver[HostGroupEvent]

        When("Creating a host group tracker")
        val tracker = new HostGroupTracker(hostGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The host emits an error")
        store.keyObservableError(host1.getId.asJava.toString, classOf[Host],
                                 host1.getId, ContainerKey, new Throwable())

        Then("The observer should receive a notification with the host removed")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe HostGroupEvent(hostGroup.getId, Map())
    }

    "Host group state" should "emit error for host group error" in {
        Given("A host in a host group")
        val host1 = createHost()
        val hostGroup = createHostGroup(host1.getId)

        And("A host group tracker observer")
        val obs = new TestObserver[HostGroupEvent]

        When("Creating a host group tracker")
        val tracker = new HostGroupTracker(hostGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The host group emits an error")
        store.observableError(classOf[HostGroup], hostGroup.getId,
                              new Throwable())

        Then("The observer should receive an error")
        obs.getOnErrorEvents should have size 1
    }
}
