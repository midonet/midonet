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

import scala.concurrent.Future
import scala.util.Random

import org.junit.runner.RunWith
import org.mockito.{Mockito, Matchers => MockMatchers}
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.rpc.State.ProxyResponse
import org.midonet.cluster.rpc.State.ProxyResponse.{Acknowledge, Notify}
import org.midonet.cluster.services.state.server.ClientHandler
import org.midonet.util.concurrent.CallingThreadExecutionContext

@RunWith(classOf[JUnitRunner])
class StateTableSubscriberTest extends FeatureSpec with Matchers
                               with GivenWhenThen {

    private val random = new Random()

    private class TestHandler extends ClientHandler {
        var closed = false
        var messages = Seq.empty[ProxyResponse]
        override def close(): Future[AnyRef] = {
            closed = true
            Future.successful(None)
        }
        override def send(message: ProxyResponse): Future[AnyRef] = {
            messages = messages :+ message
            Future.successful(None)
        }
    }

    private def newCache(): StateTableCache = {
        val cache = Mockito.mock(classOf[StateTableCache])
        Mockito.when(cache.dispatcher).thenReturn(CallingThreadExecutionContext)
        cache
    }

    private def newSubscriber(handler: ClientHandler,
                              cache: StateTableCache,
                              requestId: Long = 0L,
                              lastVersion: Option[Long] = None,
                              onComplete: (StateTableSubscriber) => Unit =
                                _ => {}): StateTableSubscriber = {
        val key = StateTableKey(classOf[Void], UUID.randomUUID(),
                                classOf[Void], classOf[Void], "name", Seq.empty)
        new StateTableSubscriber(key, handler, cache, requestId, lastVersion,
                                 onComplete)
    }


    feature("Subscriber subscribes to the cache") {
        scenario("Subscriber passes correct subscription arguments") {
            Given("A cache and a handler")
            val cache = newCache()
            val handler = new TestHandler

            When("Creating a subscriber")
            val lastVersion = random.nextLong()
            val subscriber = newSubscriber(handler, cache, requestId = 0L,
                                           lastVersion = Some(lastVersion))

            Then("The subscriber should subscribe")
            Mockito.verify(cache).subscribe(subscriber, Some(lastVersion))
        }

        scenario("Subscriber returns correct subscription information") {
            Given("A cache and a handler")
            val cache = newCache()
            val handler = new TestHandler

            And("A cache subscription")
            val subscriptionId = random.nextLong()
            val subscription = Mockito.mock(classOf[StateTableSubscription])
            Mockito.when(subscription.id).thenReturn(subscriptionId)

            And("Cache returns a subscription identifier")
            Mockito.when(cache.subscribe(MockMatchers.any(), MockMatchers.any()))
                       .thenReturn(subscription)

            When("Creating a subscriber")
            val subscriber = newSubscriber(handler, cache)

            Then("Subscriber should return the subscription identifier")
            subscriber.id shouldBe subscriptionId
            Mockito.verify(subscription).id

            And("Calling isUnsubscribed should call the subscription")
            subscriber.isUnsubscribed
            Mockito.verify(subscription).isUnsubscribed

            And("Calling unsubscribe should call the subscription")
            subscriber.unsubscribe()
            Mockito.verify(subscription).unsubscribe()
        }
    }

    feature("Subscriber handles notifications") {
        scenario("Subscriber must acknowledged subscription") {
            Given("A cache and a handler")
            val cache = newCache()
            val handler = new TestHandler

            And("A cache subscription")
            val subscriptionId = random.nextLong()
            val subscription = Mockito.mock(classOf[StateTableSubscription])
            Mockito.when(subscription.id).thenReturn(subscriptionId)
            Mockito.when(cache.subscribe(MockMatchers.any(), MockMatchers.any()))
                   .thenReturn(subscription)

            And("A subscriber")
            val subscriber = newSubscriber(handler, cache, requestId = 1L)

            When("Sending a notification")
            val notify = Notify.newBuilder()
                               .setSubscriptionId(subscriptionId).build()
            subscriber.next(notify)

            Then("The handler does not receive any message")
            handler.messages shouldBe empty

            When("Acknowledging the subscription")
            subscriber.acknowledge(requestId = 1L, lastVersion = Some(2L))

            Then("The handler receives two messages")
            handler.messages should have size 2

            And("The message should be an acknowledgement and notification")
            val ack = ProxyResponse.newBuilder()
                .setRequestId(1L)
                .setAcknowledge(Acknowledge.newBuilder()
                                           .setSubscriptionId(subscriptionId)
                                           .setLastVersion(2L))
                .build()
            val not = ProxyResponse.newBuilder()
                .setRequestId(1L)
                .setNotify(notify)
                .build()

            handler.messages should contain theSameElementsInOrderAs Seq(ack, not)

            When("Sending a next notification")
            subscriber.next(notify)

            Then("The handler immediately receives a next message")
            handler.messages should have size 3
            handler.messages(2) shouldBe not
        }

        scenario("Sending a completion calls onComplete") {
            Given("A cache and a handler")
            val cache = newCache()
            val handler = new TestHandler

            And("A cache subscription")
            val subscriptionId = random.nextLong()
            val subscription = Mockito.mock(classOf[StateTableSubscription])
            Mockito.when(subscription.id).thenReturn(subscriptionId)
            Mockito.when(cache.subscribe(MockMatchers.any(), MockMatchers.any()))
                .thenReturn(subscription)

            And("A subscriber")
            var completed = false
            val subscriber = newSubscriber(handler, cache, requestId = 1L,
                                           lastVersion = None, _ => completed = true)

            When("Acknowledging the subscription")
            subscriber.acknowledge()

            And("Sending a completion notification")
            val notify = Notify.newBuilder()
                               .setSubscriptionId(subscriptionId)
                               .setCompleted(Notify.Completed.newBuilder())
                               .build()
            subscriber.next(notify)

            Then("The handler receives two messages")
            handler.messages should have size 2

            And("The completion handler should be called")
            completed shouldBe true
        }
    }

    feature("Subscriber handles requests") {
        scenario("Subscriber handles refresh") {
            Given("A cache and a handler")
            val cache = newCache()
            val handler = new TestHandler

            And("A cache subscription")
            val subscriptionId = random.nextLong()
            val subscription = Mockito.mock(classOf[StateTableSubscription])
            Mockito.when(subscription.id).thenReturn(subscriptionId)
            Mockito.when(cache.subscribe(MockMatchers.any(), MockMatchers.any()))
                .thenReturn(subscription)

            And("A subscriber")
            val subscriber = newSubscriber(handler, cache, requestId = 1L)

            When("Requesting a refresh")
            subscriber.refresh(requestId = 2L, lastVersion = Some(3L))

            Then("The handler receives an acknowledgement")
            val ack = ProxyResponse.newBuilder()
                .setRequestId(2L)
                .setAcknowledge(Acknowledge.newBuilder()
                                    .setSubscriptionId(subscriptionId)
                                    .setLastVersion(3L))
                .build()
            handler.messages should have size 1
            handler.messages should contain only ack

            And("The subscriber should call refresh on the subscription")
            Mockito.verify(subscription).refresh(Some(3L))
        }

        scenario("Subscriber handles close on shutdown") {
            Given("A cache and a handler")
            val cache = newCache()
            val handler = new TestHandler

            And("A cache subscription")
            val subscriptionId = random.nextLong()
            val subscription = Mockito.mock(classOf[StateTableSubscription])
            Mockito.when(subscription.id).thenReturn(subscriptionId)
            Mockito.when(cache.subscribe(MockMatchers.any(), MockMatchers.any()))
                .thenReturn(subscription)

            And("A subscriber")
            val subscriber = newSubscriber(handler, cache, requestId = 1L)

            When("Calling close for shutdown")
            subscriber.close(serverInitiated = true)

            Then("The handler receives a completed notification")
            val notify = ProxyResponse.newBuilder()
                .setRequestId(1L)
                .setNotify(Notify.newBuilder()
                               .setSubscriptionId(subscriptionId)
                               .setCompleted(Notify.Completed.newBuilder()
                                                 .setCode(Notify.Completed.Code.SERVER_SHUTDOWN)
                                                 .setDescription("Server shutting down")))
                .build()
            handler.messages should have size 1
            handler.messages should contain only notify

            And("The subscription should unsubscribe")
            Mockito.verify(subscription).unsubscribe()
        }

        scenario("Subscriber handles close on connection closed") {
            Given("A cache and a handler")
            val cache = newCache()
            val handler = new TestHandler

            And("A cache subscription")
            val subscriptionId = random.nextLong()
            val subscription = Mockito.mock(classOf[StateTableSubscription])
            Mockito.when(subscription.id).thenReturn(subscriptionId)
            Mockito.when(cache.subscribe(MockMatchers.any(), MockMatchers.any()))
                .thenReturn(subscription)

            And("A subscriber")
            val subscriber = newSubscriber(handler, cache, requestId = 1L)

            When("Calling close for connection closed")
            subscriber.close(serverInitiated = false)

            Then("The handler does not receive any messages")
            handler.messages shouldBe empty

            And("The subscription should unsubscribe")
            Mockito.verify(subscription).unsubscribe()
        }
    }

}
