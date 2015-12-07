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

import scala.collection.mutable

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, GivenWhenThen, Matchers}

import rx.Observable
import rx.observers.TestObserver
import rx.subjects.BehaviorSubject

@RunWith(classOf[JUnitRunner])
class CollectionTrackerTest extends FlatSpec with SchedulersTest with BeforeAndAfter
                            with Matchers with GivenWhenThen {

    private class MemberTracker extends ObjectTracker[String] {
        private val subject = BehaviorSubject.create[String]
        override def observable: Observable[String] =
            subject.asObservable().takeUntil(mark)
        override def isReady: Boolean = subject.hasValue
        def onNext(value: String): Unit = { ref = value; subject onNext value }
        def onCompleted(): Unit  = { subject.onCompleted() }
        def onError(e: Throwable): Unit = { subject onError e }
    }

    private class TestCollectionTracker(context: Context)
        extends CollectionTracker[MemberTracker, String](context) {
        val trackers = new mutable.HashMap[UUID, MemberTracker]
        override def newMember(memberId: UUID): MemberTracker = {
            trackers.getOrElseUpdate(memberId, new MemberTracker)
        }
        def onNext(memberId: UUID, value: String) =
            trackers.get(memberId).get.onNext(value)
        def onCompleted(memberId: UUID) =
            trackers.get(memberId).get.onCompleted()
        def onError(memberId: UUID, e: Throwable) =
            trackers.get(memberId).get.onError(e)
    }

    "Collection tracker" should "not emit before adding member" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)

        And("A collection tracker observer")

        val obs = new TestObserver[Map[UUID, String]]

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should not receive a notification")
        obs.getOnNextEvents shouldBe empty
        obs.getOnCompletedEvents shouldBe empty
        obs.getOnErrorEvents shouldBe empty
    }

    "Collection tracker" should "emit for member added after subscription" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)
        val id1 = UUID.randomUUID

        And("A collection tracker observer")
        val obs = new TestObserver[Map[UUID, String]]

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        And("Adding the member to the collection")
        tracker add id1

        Then("The observer should not receive a notification")
        obs.getOnNextEvents shouldBe empty

        When("The member tracker emits a value")
        tracker.onNext(id1, "1")

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe Map(id1 -> "1")
    }

    "Collection tracker" should "not emit for member added before subscription" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)
        val id1 = UUID.randomUUID

        And("A collection tracker observer")
        val obs = new TestObserver[Map[UUID, String]]

        And("Adding the member to the collection")
        tracker add id1

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents shouldBe empty
    }

    "Collection tracker" should "emit for no members" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)

        And("A collection tracker observer")
        val obs = new TestObserver[Map[UUID, String]]

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        And("Adding no members to the collection")
        tracker watch Set()

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe Map()
    }


    "Collection tracker" should "emit for several members" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)
        val id1 = UUID.randomUUID
        val id2 = UUID.randomUUID
        val id3 = UUID.randomUUID

        And("A collection tracker observer")
        val obs = new TestObserver[Map[UUID, String]]

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        And("Adding the members to the collection")
        tracker watch Set(id1, id2, id3)

        And("Only one member emits a value")
        tracker.onNext(id1, "1")

        Then("The observer should not receive a notification")
        obs.getOnNextEvents shouldBe empty

        When("All members emitted values")
        tracker.onNext(id2, "2")
        tracker.onNext(id3, "3")

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe Map(id1 -> "1", id2 -> "2", id3 -> "3")
    }

    "Collection tracker" should "filter duplicate notifications" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)
        val id1 = UUID.randomUUID
        val id2 = UUID.randomUUID
        val id3 = UUID.randomUUID

        And("A collection tracker observer")
        val obs = new TestObserver[Map[UUID, String]]

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        And("Adding the members to the collection")
        tracker watch Set(id1, id2, id3)
        tracker.onNext(id1, "1")
        tracker.onNext(id2, "2")
        tracker.onNext(id3, "3")

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("Requesting the same members")
        tracker watch Set(id1, id2, id3)

        Then("The observer should not receive another notification")
        obs.getOnNextEvents should have size 1
    }

    "Collection tracker" should "perform the difference between members" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)
        val id1 = UUID.randomUUID
        val id2 = UUID.randomUUID
        val id3 = UUID.randomUUID
        val id4 = UUID.randomUUID

        And("A collection tracker observer")
        val obs = new TestObserver[Map[UUID, String]]

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        And("Adding the members to the collection")
        tracker watch Set(id1, id2, id3)
        tracker.onNext(id1, "1")
        tracker.onNext(id2, "2")
        tracker.onNext(id3, "3")

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("Removing some hosts and adding new ones")
        tracker watch Set(id2, id3, id4)

        Then("The observer should not emit a notification")
        obs.getOnNextEvents should have size 1

        When("The new member emits a value")
        tracker.onNext(id4, "4")

        Then("The observer should receive another notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe Map(id2 -> "2", id3 -> "3", id4 -> "4")
    }

    "Collection tracker" should "allow adding members" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)
        val id1 = UUID.randomUUID
        val id2 = UUID.randomUUID

        And("A collection tracker observer")
        val obs = new TestObserver[Map[UUID, String]]

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        And("Adding the member to the collection")
        tracker watch Set(id1)

        Then("The observer should not receive a notification")
        obs.getOnNextEvents shouldBe empty

        When("The member emits a value")
        tracker.onNext(id1, "1")

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("Adding a new memver")
        tracker add id2
        tracker.onNext(id2, "2")

        Then("The observer should receive another notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe Map(id1 -> "1", id2 -> "2")
    }

    "Collection tracker" should "allow removing members" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)
        val id1 = UUID.randomUUID
        val id2 = UUID.randomUUID

        And("A collection tracker observer")
        val obs = new TestObserver[Map[UUID, String]]

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        And("Adding the members to the collection")
        tracker watch Set(id1, id2)
        tracker.onNext(id1, "1")
        tracker.onNext(id2, "2")

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("Removing a member")
        tracker remove id1

        Then("The observer should receive another notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe Map(id2 -> "2")
    }

    "Collection tracker" should "emit member notifications" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)
        val id1 = UUID.randomUUID
        val id2 = UUID.randomUUID
        val id3 = UUID.randomUUID

        And("A collection tracker observer")
        val obs = new TestObserver[Map[UUID, String]]

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        And("Adding the members to the collection")
        tracker watch Set(id1, id2, id3)
        tracker.onNext(id1, "1")
        tracker.onNext(id2, "2")
        tracker.onNext(id3, "3")

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("One member emits another value")
        tracker.onNext(id1, "4")

        Then("The observer should receive another notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe Map(id1 -> "4", id2 -> "2", id3 -> "3")

        When("The second member emits another value")
        tracker.onNext(id2, "5")

        Then("The observer should receive another notification")
        obs.getOnNextEvents should have size 3
        obs.getOnNextEvents.get(2) shouldBe Map(id1 -> "4", id2 -> "5", id3 -> "3")
    }

    "Collection tracker" should "remove completed members" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)
        val id1 = UUID.randomUUID
        val id2 = UUID.randomUUID
        val id3 = UUID.randomUUID

        And("A collection tracker observer")
        val obs = new TestObserver[Map[UUID, String]]

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        And("Adding the members to the collection")
        tracker watch Set(id1, id2, id3)
        tracker.onNext(id1, "1")
        tracker.onNext(id2, "2")
        tracker.onNext(id3, "3")

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("A member completes")
        tracker.onCompleted(id1)

        Then("The observer should receive a notification with member removed")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe Map(id2 -> "2", id3 -> "3")
    }

    "Collection tracker" should "handle member errors" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)
        val id1 = UUID.randomUUID
        val id2 = UUID.randomUUID
        val id3 = UUID.randomUUID

        And("A collection tracker observer")
        val obs = new TestObserver[Map[UUID, String]]

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        And("Adding the members to the collection")
        tracker watch Set(id1, id2, id3)
        tracker.onNext(id1, "1")
        tracker.onNext(id2, "2")
        tracker.onNext(id3, "3")

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("A member emits an error")
        tracker.onError(id1, new Throwable)

        Then("The observer should receive a notification with member removed")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe Map(id2 -> "2", id3 -> "3")
    }

    "Collection tracker" should "complete when complete method is called" in {
        Given("A collection tracker")
        val tracker = new TestCollectionTracker(context)
        val id1 = UUID.randomUUID

        And("A collection tracker observer")
        val obs = new TestObserver[Map[UUID, String]]

        When("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        And("Adding a member to the collection")
        tracker add id1
        tracker.onNext(id1, "1")

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("Calling the complete method")
        tracker.complete()

        Then("The observer should receive a completed notification")
        obs.getOnCompletedEvents should have size 1
    }
}
