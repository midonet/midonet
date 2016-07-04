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

package org.midonet.util.reactivex

import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import rx.Subscriber

@RunWith(classOf[JUnitRunner])
class SubscriptionListTest extends FlatSpec with Matchers with GivenWhenThen {

    private class TestableList(unsubscribeOnAdd: Boolean = false)
        extends SubscriptionList[Int] {
        @volatile var started = Seq.empty[Subscriber[_ >: Int]]
        @volatile var added = Seq.empty[Subscriber[_ >: Int]]
        @volatile var terminated = Seq.empty[Subscriber[_ >: Int]]

        protected override def start(child: Subscriber[_ >: Int]): Unit = {
            this.synchronized { started = started :+ child }
        }

        protected override def added(child: Subscriber[_ >: Int]): Unit = {
            this.synchronized { added = added :+ child }
            if (unsubscribeOnAdd) child.unsubscribe()
        }

        protected override def terminated(child: Subscriber[_ >: Int]): Unit = {
            this.synchronized { terminated = terminated :+ child }
        }

        def subs = subscribers

        def close() = terminate()
    }

    private class TestableSubscriber extends Subscriber[Int] {
        override def onNext(value: Int): Unit = { }
        override def onCompleted(): Unit = { }
        override def onError(e: Throwable): Unit = { }
    }

    "List" should "handle a single subscriber" in {
        Given("A subscription list")
        val list = new TestableList

        And("A subscriber")
        val subscriber = new TestableSubscriber

        Then("The list is empty")
        list.subs shouldBe empty

        When("Adding the subscriber to the list")
        list.call(subscriber)

        Then("The list should contain the subscriber")
        list.subs should contain only subscriber

        And("The list should call started and added")
        list.started should contain only subscriber
        list.added should contain only subscriber

        When("The subscriber unsubscribes")
        subscriber.unsubscribe()

        Then("The list is empty")
        list.subs shouldBe empty
    }

    "List" should "handle unsubscribe during add" in {
        Given("A subscription list")
        val list = new TestableList(unsubscribeOnAdd = true)

        And("A subscriber")
        val subscriber = new TestableSubscriber

        Then("The list is empty")
        list.subs shouldBe empty

        When("Adding the subscriber to the list")
        list.call(subscriber)

        Then("The list is empty")
        list.subs shouldBe empty

        And("The list should call started and added")
        list.started should contain only subscriber
        list.added should contain only subscriber
    }

    "List" should "handle multiple subscribers" in {
        Given("A subscription list")
        val list = new TestableList

        And("Two subscribers")
        val subscriber1 = new TestableSubscriber
        val subscriber2 = new TestableSubscriber

        When("Adding the subscriber to the list")
        list.call(subscriber1)

        Then("The list should contain the subscriber")
        list.subs should contain only subscriber1

        When("Adding a second subscriber to the list")
        list.call(subscriber2)

        Then("The list should contain the subscriber")
        list.subs should contain allOf (subscriber1, subscriber2)

        And("The list should call started and added")
        list.started should contain allOf (subscriber1, subscriber2)
        list.added should contain allOf (subscriber1, subscriber2)

        When("The first subscriber unsubscribes")
        subscriber1.unsubscribe()

        Then("The list should contain the second subscriber")
        list.subs should contain only subscriber2

        When("The second subscriber unsubscribes")
        subscriber2.unsubscribe()

        Then("The list is empty")
        list.subs shouldBe empty
    }

    "List" should "return current subscriptions on close" in {
        Given("A subscription list")
        val list = new TestableList

        And("Two subscribers")
        val subscriber1 = new TestableSubscriber
        val subscriber2 = new TestableSubscriber

        When("Adding the subscribers to the list")
        list.call(subscriber1)
        list.call(subscriber2)

        Then("Closing the list should return the subscribers")
        list.close() should contain allOf(subscriber1, subscriber2)

        And("Closing the list a second time return no subscribers")
        list.close() shouldBe empty
    }

    "List" should "handle subscribe after close" in {
        Given("A subscription list")
        val list = new TestableList

        And("A subscriber")
        val subscriber = new TestableSubscriber

        When("Closing the list")
        list.close() shouldBe empty

        And("Adding the subscriber to the list")
        list.call(subscriber)

        Then("The list is empty")
        list.subs shouldBe empty

        And("The list should call started and terminated")
        list.started should contain only subscriber
        list.terminated should contain only subscriber
    }

    "List" should "handle unsubscribe after close" in {
        Given("A subscription list")
        val list = new TestableList

        And("A subscriber")
        val subscriber = new TestableSubscriber

        And("Adding the subscriber to the list")
        list.call(subscriber)

        When("Closing the list")
        list.close() should contain only subscriber

        And("Unsubscribing the subscriber")
        subscriber.unsubscribe()

        Then("The subscriber is unsubscribed")
        subscriber.isUnsubscribed shouldBe true
    }

    "List" should "handle concurrent updates" in {
        Given("A subscription list")
        val list = new TestableList

        When("Adding and removing subscribers from several threads")
        val threads = for (index <- 0 until 10) yield new Thread(new Runnable {
            override def run(): Unit = {
                val subs = for (sub <- 0 until 100) yield new TestableSubscriber
                for (sub <- subs) {
                    list.call(sub)
                }
                for (sub <- subs) {
                    sub.unsubscribe()
                }
            }
        })

        for (thread <- threads) {
            thread.start()
        }

        for (thread <- threads) {
            thread.join()
        }

        Then("The list should be empty")
        list.close() shouldBe empty

        And("The list should have added 1000 subscribers")
        list.started should have size 1000
        list.added should have size 1000
    }

}
