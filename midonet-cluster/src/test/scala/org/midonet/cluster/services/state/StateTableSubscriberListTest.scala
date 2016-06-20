/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.state

import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.util.Random

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import sun.plugin.dom.exception.InvalidStateException

import org.midonet.util.functors.makeRunnable

@RunWith(classOf[JUnitRunner])
class StateTableSubscriberListTest extends FlatSpec with Matchers with GivenWhenThen {

    private class TestStateTableSubscriber(key: StateTableKey)
        extends StateTableSubscriber(key, handler = null, cache = null,
                                     requestId = 0L, lastVersion = None,
                                     onComplete = _ => {}) {
    }

    private val random = new Random()

    private def randomKey(): StateTableKey = {
        new StateTableKey(classOf[Void], UUID.randomUUID(), classOf[Void],
                          classOf[Void], "name", Seq.empty)
    }

    private def newSubscriber(key: StateTableKey): StateTableSubscriber = {
        val subscriber = Mockito.mock(classOf[StateTableSubscriber])
        val subscriptionId = random.nextLong()
        Mockito.when(subscriber.key).thenReturn(key)
        Mockito.when(subscriber.id).thenReturn(subscriptionId)
        subscriber
    }

    "List" should "get or else update subscribers" in {
        Given("A subscriber list and a table key")
        val list = new StateTableSubscriberList
        val key = randomKey()

        When("Requesting a subscriber for the table")
        var create = 0
        var delete = 0
        var onNew = 0
        var onExisting = 0
        val subscriber1 = list.getOrElseUpdate(
            key, { create += 1; newSubscriber(key) },
            { _ => delete += 1 }, { _ => onNew += 1 }, { _ => onExisting += 1 })

        Then("The list should return a subscriber")
        subscriber1 should not be null

        And("The handler methods should be called")
        create shouldBe 1
        delete shouldBe 0
        onNew shouldBe 1
        onExisting shouldBe 0

        When("Requesting the subscriber a second time")
        val subscriber2 = list.getOrElseUpdate(
            key, { create += 1; newSubscriber(key) },
            { _ => delete += 1 }, { _ => onNew += 1 }, { _ => onExisting += 1 })

        Then("The list should return the same subscriber instance")
        subscriber2 eq subscriber1 shouldBe true

        And("The handler methods should be called")
        create shouldBe 1
        delete shouldBe 0
        onNew shouldBe 1
        onExisting shouldBe 1

        And("The list should contain the subscription")
        list.close() should contain only subscriber1
    }

    "List" should "support multiple subscribers" in {
        Given("A subscriber list and several table keys")
        val list = new StateTableSubscriberList
        val keys = Seq(randomKey(), randomKey(), randomKey())

        When("Requesting a subscriber for all keys")
        var create = 0
        var delete = 0
        var onNew = 0
        var onExisting = 0

        Then("The handler methods should be called")
        val subscribers = for (index <- 0 until 3) yield {
            val subscriber = list.getOrElseUpdate(
                keys(index), { create += 1; newSubscriber(keys(index)) },
                { _ => delete += 1 }, { _ => onNew += 1 },
                { _ => onExisting += 1 })

            create shouldBe index + 1
            delete shouldBe 0
            onNew shouldBe index + 1
            onExisting shouldBe 0

            subscriber
        }

        And("The list should different subscribers")
        subscribers should have size 3
        for (subscriber <- subscribers) {
            subscriber should not be null
        }

        When("Requesting a subscriber for the second key")
        val subscriber2 = list.getOrElseUpdate(
            keys(1), { create += 1; newSubscriber(keys(1)) },
            { _ => delete += 1 }, { _ => onNew += 1 }, { _ => onExisting += 1 })

        Then("The list should return the second subscriber")
        subscriber2 eq subscribers(1) shouldBe true

        And("The handler methods should be called")
        create shouldBe 3
        delete shouldBe 0
        onNew shouldBe 3
        onExisting shouldBe 1

        When("Removing the first subscriber by subscription identifier")
        list.remove(subscribers(0).id)

        And("Requesting a subscriber for the first key")
        val subscriber1 = list.getOrElseUpdate(
            keys.head, { create += 1; newSubscriber(keys.head) },
            { _ => delete += 1 }, { _ => onNew += 1 }, { _ => onExisting += 1 })

        And("The handler methods should be called")
        create shouldBe 4
        delete shouldBe 0
        onNew shouldBe 4
        onExisting shouldBe 1

        And("The new subscriber should have a different subscription identifier")
        subscriber1.id should not be subscribers(0).id

        When("Removing the third subscriber")
        list.remove(subscribers(2))

        And("Requesting a subscriber for the third key")
        val subscriber3 = list.getOrElseUpdate(
            keys(2), { create += 1; newSubscriber(keys(2)) },
            { _ => delete += 1 }, { _ => onNew += 1 }, { _ => onExisting += 1 })

        And("The handler methods should be called")
        create shouldBe 5
        delete shouldBe 0
        onNew shouldBe 5
        onExisting shouldBe 1

        And("Closing the list the lastest subscribers")
        list.close() should contain allOf (subscriber1, subscriber2, subscriber3)
    }

    "List" should "handle concurrent additions" in {
        Given("A subscriber list and a table key")
        val list = new StateTableSubscriberList
        val key = randomKey()

        When("Requesting a subscriber for the table from several threads")
        val create = new AtomicInteger()
        val delete = new AtomicInteger()
        val onNew = new AtomicInteger()
        val onExisting = new AtomicInteger()
        val subscriber = new AtomicReference[StateTableSubscriber](null)

        Then("The list always returns the same subscriber")
        val threads = for (index <- 0 until 10) yield {
            new Thread(makeRunnable {
                val sub = list.getOrElseUpdate(
                    key, { create.incrementAndGet(); newSubscriber(key) },
                    { _ => delete.incrementAndGet() },
                    { _ => onNew.incrementAndGet() },
                    { _ => onExisting.incrementAndGet() })
                if (!subscriber.compareAndSet(null, sub)) {
                    sub eq subscriber.get shouldBe true
                }
            })
        }

        for (thread <- threads){
            thread.start()
        }

        for (thread <- threads) {
            thread.join()
        }

        And("The list should call delete for unused subscribers")
        create.get shouldBe delete.get + 1

        And("Only one new subscriber is used")
        onNew.get shouldBe 1
        onExisting.get shouldBe 9

        Then("The list has only one entry")
        list.close() should contain only subscriber.get
    }

    "List" should "handle concurrent removals" in {
        Given("A subscriber list and a table key")
        val list = new StateTableSubscriberList
        val key = randomKey()

        When("Requesting a subscriber for the table")
        val subscriber = list.getOrElseUpdate(
            key, newSubscriber(key), _ => { }, _ => { }, _ => { })

        Then("Removing the subscriber from multiple threads should only return " +
             "the subscriber once")
        val removedSubscriber = new AtomicReference[StateTableSubscriber](null)
        val threads = for (index <- 0 until 10) yield {
            new Thread(makeRunnable {
                val sub = list.remove(subscriber.id)
                if (!removedSubscriber.compareAndSet(null, sub)) {
                    sub shouldBe null
                }
            })
        }

        for (thread <- threads){
            thread.start()
        }

        for (thread <- threads) {
            thread.join()
        }

    }

    "List" should "remove subscriber by subscription identifier" in {
        Given("A subscriber list and a table key")
        val list = new StateTableSubscriberList
        val key = randomKey()

        When("Requesting a subscriber")
        val subscriber = list.getOrElseUpdate(
            key, newSubscriber(key), _ => { }, _ => { }, _ => { })

        And("Removing the subscriber")
        list.remove(subscriber.id)

        Then("The list should be empty")
        list.close() shouldBe empty
    }

    "List" should "remove subscriber" in {
        Given("A subscriber list and a table key")
        val list = new StateTableSubscriberList
        val key = randomKey()

        When("Requesting a subscriber")
        val subscriber = list.getOrElseUpdate(
            key, newSubscriber(key), _ => { }, _ => { }, _ => { })

        And("Removing the subscriber")
        list.remove(subscriber)

        Then("The list should be empty")
        list.close() shouldBe empty
    }

    "List" should "support idempotent remove" in {
        Given("A subscriber list and a table key")
        val list = new StateTableSubscriberList
        val key = randomKey()

        When("Requesting a subscriber")
        val subscriber = list.getOrElseUpdate(
            key, newSubscriber(key), _ => { }, _ => { }, _ => { })

        And("Removing the subscriber twice")
        list.remove(subscriber)
        list.remove(subscriber)

        Then("The list should be empty")
        list.close() shouldBe empty
    }

    "List" should "throw exception on get if closed" in {
        Given("A subscriber list and a table key")
        val list = new StateTableSubscriberList
        val key = randomKey()

        When("Closing the list")
        list.close() shouldBe empty

        Then("The list should be closed")
        list.isClosed shouldBe true

        And("Requesting a subscriber should fail")
        intercept[InvalidStateException] {
            list.getOrElseUpdate(key, newSubscriber(key), _ => { }, _ => { },
                                 _ => { })
        }
    }

    "List" should "throw exception on remove by subscription if closed" in {
        Given("A subscriber list and a subscription identifier")
        val list = new StateTableSubscriberList
        val subscriptionId = random.nextLong()

        When("Closing the list")
        list.close() shouldBe empty

        Then("Removing the subscription by identifier should fail")
        intercept[InvalidStateException] {
            list.remove(subscriptionId)
        }
    }

    "List" should "ignore remove by subscriber if closed" in {
        Given("A subscriber list and a subscriber")
        val list = new StateTableSubscriberList
        val subscriber = newSubscriber(randomKey())

        When("Closing the list")
        list.close() shouldBe empty

        Then("Removing the subscription by identifier is idempotent")
        list.remove(subscriber)
    }

}
