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

import org.junit.runner.RunWith
import org.scalatest.{GivenWhenThen, Matchers, BeforeAndAfter, FlatSpec}
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class AnywhereHostSelectorTest extends FlatSpec with SchedulersTest with BeforeAndAfter
                               with Matchers with GivenWhenThen {

    "Anywhere selector" should "emit an empty set when no hosts exist" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("A selector observer")
        val obs = new TestObserver[HostsEvent]

        When("The observer subscribes to the selector")
        selector.observable subscribe obs

        Then("The observer should receive an empty set")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe empty
    }

    "Anywhere selector" should "emit existing hosts" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("A selector observer")
        val obs = new TestObserver[HostsEvent]

        And("Two hosts")
        val host1 = createHost()
        val host2 = createHost()

        When("The observer subscribes to the selector")
        selector.observable subscribe obs

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false),
            host2.getId.asJava -> HostEvent(running = false))
    }

    "Anywhere selector" should "emit added hosts" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("A selector observer")
        val obs = new TestObserver[HostsEvent]

        When("The observer subscribes to the selector")
        selector.observable subscribe obs

        Then("The observer should receive an empty set")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe empty

        When("Adding a first host")
        val host1 = createHost()

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false))

        When("Adding a second host")
        val host2 = createHost()

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 3
        obs.getOnNextEvents.get(2) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false),
            host2.getId.asJava -> HostEvent(running = false))
    }

    "Anywhere selector" should "emit deleted hosts" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("A selector observer")
        val obs = new TestObserver[HostsEvent]

        And("Two hosts")
        val host1 = createHost()
        val host2 = createHost()

        When("The observer subscribes to the selector")
        selector.observable subscribe obs

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 1

        When("Deleting the first host")
        store.delete(classOf[Host], host1.getId)

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe Map(
            host2.getId.asJava -> HostEvent(running = false))

        When("Deleting the second host")
        store.delete(classOf[Host], host2.getId)

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 3
        obs.getOnNextEvents.get(2) shouldBe Map()
    }

    "Anywhere selector" should "emit when hosts add status" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("A selector observer")
        val obs = new TestObserver[HostsEvent]

        And("Two hosts")
        val host1 = createHost()
        val host2 = createHost()

        When("The observer subscribes to the selector")
        selector.observable subscribe obs

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 1

        When("The first host updates the status")
        val status1 = createHostStatus(host1.getId)

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = true, status1),
            host2.getId.asJava -> HostEvent(running = false))

        When("The second host updates the status")
        val status2 = createHostStatus(host2.getId)

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 3
        obs.getOnNextEvents.get(2) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = true, status1),
            host2.getId.asJava -> HostEvent(running = true, status2))
    }

    "Anywhere selector" should "emit when hosts remove status" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("A selector observer")
        val obs = new TestObserver[HostsEvent]

        And("Two hosts")
        val host1 = createHost()
        val host2 = createHost()
        val status1 = createHostStatus(host1.getId)
        val status2 = createHostStatus(host2.getId)

        When("The observer subscribes to the selector")
        selector.observable subscribe obs

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = true, status1),
            host2.getId.asJava -> HostEvent(running = true, status2))

        When("Removing the status for the first host")
        deleteHostStatus(host1.getId)

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false),
            host2.getId.asJava -> HostEvent(running = true, status2))

        When("Removing the status for the second host")
        deleteHostStatus(host2.getId)

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 3
        obs.getOnNextEvents.get(2) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false),
            host2.getId.asJava -> HostEvent(running = false))
    }

    "Anywhere selector" should "support multiple initial subscribers" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("Two selector observer")
        val obs1 = new TestObserver[HostsEvent]
        val obs2 = new TestObserver[HostsEvent]

        And("Two hosts")
        val host1 = createHost()
        val host2 = createHost()

        When("The first observer subscribes to the selector")
        selector.observable subscribe obs1

        Then("The first observer should receive the hosts")
        obs1.getOnNextEvents should have size 1
        obs1.getOnNextEvents.get(0) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false),
            host2.getId.asJava -> HostEvent(running = false))

        When("The second observer subscribes to the selector")
        selector.observable subscribe obs2

        Then("The second observer should receive the hosts")
        obs2.getOnNextEvents should have size 1
        obs2.getOnNextEvents.get(0) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false),
            host2.getId.asJava -> HostEvent(running = false))
    }

    "Anywhere selector" should "support multiple sequential subscribers" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("Three selector observer")
        val obs1 = new TestObserver[HostsEvent]
        val obs2 = new TestObserver[HostsEvent]

        And("A first host")
        val host1 = createHost()

        When("The first observer subscribes to the selector")
        selector.observable subscribe obs1

        Then("The first observer should receive the hosts")
        obs1.getOnNextEvents should have size 1
        obs1.getOnNextEvents.get(0) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false))

        When("Adding a second host")
        val host2 = createHost()

        Then("The first observer should receive the hosts")
        obs1.getOnNextEvents should have size 2
        obs1.getOnNextEvents.get(1) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false),
            host2.getId.asJava -> HostEvent(running = false))

        When("The second observer subscribes to the selector")
        selector.observable subscribe obs2

        Then("The observer should receive the hosts")
        obs2.getOnNextEvents should have size 1
        obs2.getOnNextEvents.get(0) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false),
            host2.getId.asJava -> HostEvent(running = false))

        When("Adding a third host")
        val host3 = createHost()

        Then("Both observers should receive the hosts")
        obs1.getOnNextEvents should have size 3
        obs1.getOnNextEvents.get(2) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false),
            host2.getId.asJava -> HostEvent(running = false),
            host3.getId.asJava -> HostEvent(running = false))
        obs2.getOnNextEvents should have size 2
        obs2.getOnNextEvents.get(1) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false),
            host2.getId.asJava -> HostEvent(running = false),
            host3.getId.asJava -> HostEvent(running = false))
    }

    "Anywhere selector" should "allow multiple subscribers unsubscribe" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("Two selector observer")
        val obs1 = new TestObserver[HostsEvent]
        val obs2 = new TestObserver[HostsEvent]

        And("Two hosts")
        val host1 = createHost()
        val host2 = createHost()

        When("Both observers subscribe to the selector")
        selector.observable subscribe obs1
        val sub2 = selector.observable subscribe obs2

        Then("Both observers should receive the hosts")
        obs1.getOnNextEvents should have size 1
        obs2.getOnNextEvents should have size 1

        When("The second observer unsubscribes")
        sub2.unsubscribe()

        And("Deleting one host")
        store.delete(classOf[Host], host1.getId)

        Then("The first observer should receive the hosts")
        obs1.getOnNextEvents should have size 2
        obs1.getOnNextEvents.get(1) shouldBe Map(
            host2.getId.asJava -> HostEvent(running = false))

        And("The second observer should not receive the hosts")
        obs2.getOnNextEvents should have size 1
    }

    "Anywhere selector" should "unsubscribe" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("Two selector observer")
        val obs1 = new TestObserver[HostsEvent]
        val obs2 = new TestObserver[HostsEvent]

        And("A host")
        createHost()

        When("Both observers subscribe to the selector")
        val sub1 = selector.observable subscribe obs1
        val sub2 = selector.observable subscribe obs2

        Then("Both observers should receive the hosts")
        obs1.getOnNextEvents should have size 1
        obs2.getOnNextEvents should have size 1

        When("Both observers unsubscribe")
        sub1.unsubscribe()
        sub2.unsubscribe()

        Then("The selector should be unsubscribed")
        selector.isUnsubscribed shouldBe true
    }

    "Anywhere selector" should "re-subscribe with hosts" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("A selector observer")
        val obs = new TestObserver[HostsEvent]

        And("Two hosts")
        val host1 = createHost()
        val host2 = createHost()

        When("The observer subscribes to the selector")
        val sub1 = selector.observable subscribe obs

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 1

        When("The observer unsubscribe")
        sub1.unsubscribe()

        Then("The selector should be unsubscribed")
        selector.isUnsubscribed shouldBe true

        When("The observer subscribes a second time")
        selector.observable subscribe obs

        Then("The observer should receive the hosts a second time")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe Map(
            host1.getId.asJava -> HostEvent(running = false),
            host2.getId.asJava -> HostEvent(running = false))

        And("The selector should be subscribed")
        selector.isUnsubscribed shouldBe false
    }

    "Anywhere selector" should "re-subscribe without hosts" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("A selector observer")
        val obs = new TestObserver[HostsEvent]

        When("The observer subscribes to the selector")
        val sub1 = selector.observable subscribe obs

        Then("The observer should receive the hosts")
        obs.getOnNextEvents should have size 1

        When("The observer unsubscribe")
        sub1.unsubscribe()

        Then("The selector should be unsubscribed")
        selector.isUnsubscribed shouldBe true

        When("The observer subscribes a second time")
        selector.observable subscribe obs

        Then("The observer should receive the hosts a second time")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe Map()

        And("The selector should be subscribed")
        selector.isUnsubscribed shouldBe false
    }

    "Anywhere selector" should "complete when complete method is called" in {
        Given("An anywhere host selector")
        val selector = new AnywhereHostSelector(context)

        And("Two selector observers")
        val obs1 = new TestObserver[HostsEvent]
        val obs2 = new TestObserver[HostsEvent]

        When("The observer subscribes to the selector")
        selector.observable subscribe obs1

        Then("The observer should receive the hosts")
        obs1.getOnNextEvents should have size 1

        When("Calling the complete method")
        selector.complete()

        Then("The observer should receive on completed")
        obs1.getOnCompletedEvents should have size 1

        When("A second observer subscribes")
        selector.observable subscribe obs2

        Then("The observer should receive only on completed")
        obs2.getOnNextEvents shouldBe empty
        obs2.getOnCompletedEvents should have size 1
    }

}
