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

package org.midonet.cluster.services.state.server

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.Random

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Matchers.any
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.rpc.State.ProxyResponse
import org.midonet.cluster.rpc.State.ProxyResponse.{Acknowledge, Notify}
import org.midonet.cluster.rpc.State.ProxyResponse.Error.Code
import org.midonet.cluster.services.state.{StateTableCache, StateTableException, StateTableKey, StateTableSubscription}
import org.midonet.util.functors._

@RunWith(classOf[JUnitRunner])
class ClientContextTest extends FlatSpec with Matchers with GivenWhenThen {

    private val random = new Random()
    private val timeout = 5 seconds

    private object ImmediateExecutionContext extends ExecutionContextExecutor {
        override def execute(runnable: Runnable): Unit = runnable.run()
        override def reportFailure(@deprecatedName('t) cause: Throwable): Unit = { }
    }

    private class TestStateTableSubscription extends StateTableSubscription {
        var lastRefresh: Option[Long] = None
        private var unsubscribed = false
        override def isUnsubscribed: Boolean = unsubscribed
        override def refresh(lastVersion: Option[Long]): Unit = {
            lastRefresh = lastVersion
        }
        override def unsubscribe(): Unit = { unsubscribed = true }
        override val id: Long = random.nextLong()
    }

    private class MultiStateTableSubscription extends StateTableSubscription {
        val unsubscribed = new AtomicInteger()
        val refresh = new AtomicInteger()
        override def isUnsubscribed: Boolean = false
        override def refresh(lastVersion: Option[Long]): Unit = {
            refresh.incrementAndGet()
        }
        override def unsubscribe(): Unit = { unsubscribed.incrementAndGet() }
        override val id: Long = random.nextLong()
    }

    private def randomKey(): StateTableKey = {
        new StateTableKey(classOf[Void], UUID.randomUUID(), classOf[Void],
                          classOf[Void], "name", Seq.empty)
    }


    private def acknowledge(requestId: Long, subscriptionId: Long,
                            lastVersion: Option[Long]): ProxyResponse = {
        val acknowledge = Acknowledge.newBuilder()
                                     .setSubscriptionId(subscriptionId)
        if (lastVersion.isDefined)
            acknowledge.setLastVersion(lastVersion.get)
        ProxyResponse.newBuilder()
            .setRequestId(requestId)
            .setAcknowledge(acknowledge)
            .build()
    }

    private def notifyCompleted(requestId: Long,
                                subscriptionId: Long): ProxyResponse = {
        val notify = Notify.newBuilder()
            .setSubscriptionId(subscriptionId)
            .setCompleted(Notify.Completed.newBuilder()
                              .setCode(Notify.Completed.Code.SERVER_SHUTDOWN)
                              .setDescription("Server shutting down"))
        ProxyResponse.newBuilder()
            .setRequestId(requestId)
            .setNotify(notify)
            .build()
    }

    "Context" should "allow subscriptions if not closed" in {
        Given("A client context")
        val handler = Mockito.mock(classOf[ClientHandler])
        Mockito.when(handler.send(any())).thenReturn(Future.successful(null))
        val context = new ClientContext(handler)

        When("A client subscribes to a table")
        val key = randomKey()
        val cache = Mockito.mock(classOf[StateTableCache])
        val subscription = new TestStateTableSubscription
        Mockito.when(cache.subscribe(any(), any())).thenReturn(subscription)
        val requestId1 = random.nextLong()
        val lastVersion = Some(random.nextLong())
        val subscriptionId = context.subscribeTo(key, cache, requestId1,
                                                 lastVersion)

        Then("The subscription identifier should match the subscription")
        subscriptionId shouldBe subscription.id

        And("The subscriber should be subscribed")
        subscription.isUnsubscribed shouldBe false

        And("The context should acknowledge the subscription")
        Mockito.verify(handler).send(acknowledge(requestId1, subscriptionId,
                                                 lastVersion))

        When("The client unsubscribes from a table")
        val requestId2 = random.nextLong()
        context.unsubscribeFrom(subscriptionId, requestId2)

        Then("The context should acknowledge the unsubscribe")
        Mockito.verify(handler).send(acknowledge(requestId2, subscriptionId,
                                                 None))
    }

    "Context" should "throw SERVER_SHUTDOWN if closed" in {
        Given("A client context")
        val handler = Mockito.mock(classOf[ClientHandler])
        Mockito.when(handler.close()).thenReturn(Future.successful(null))
        val context = new ClientContext(handler)

        When("Closing the context")
        Await.result(context.close(serverInitiated = true), timeout)

        Then("A client subscribing to a table should fail")
        val cache = Mockito.mock(classOf[StateTableCache])
        val e1 = intercept[StateTableException] {
            context.subscribeTo(randomKey(), cache, random.nextLong(), None)
        }
        e1.code shouldBe Code.SERVER_SHUTDOWN

        And("A client unsubscribing from a table should fail")
        val e2 = intercept[StateTableException] {
            context.unsubscribeFrom(random.nextLong(), random.nextLong())
        }
        e2.code shouldBe Code.SERVER_SHUTDOWN
    }

    "Context" should "throw NO_SUBSCRIPTION if subscription not found" in {
        Given("A client context")
        val handler = Mockito.mock(classOf[ClientHandler])
        Mockito.when(handler.close()).thenReturn(Future.successful(null))
        val context = new ClientContext(handler)

        Then("Unsubscribing a random subscription should fail")
        val e = intercept[StateTableException] {
            context.unsubscribeFrom(random.nextLong(), random.nextLong())
        }
        e.code shouldBe Code.NO_SUBSCRIPTION
    }

    "Context" should "close current subscriptions" in {
        Given("A client context")
        val handler = Mockito.mock(classOf[ClientHandler])
        Mockito.when(handler.send(any())).thenReturn(Future.successful(null))
        Mockito.when(handler.close()).thenReturn(Future.successful(null))
        val context = new ClientContext(handler)
        val cache = Mockito.mock(classOf[StateTableCache])

        When("A first client subscribes to a table")
        val subscription1 = new TestStateTableSubscription
        val key1 = randomKey()
        Mockito.when(cache.subscribe(any(), any())).thenReturn(subscription1)
        val subscriptionId1 = context.subscribeTo(key1, cache, 0L, None)

        Then("The subscription identifier should match the subscription")
        subscriptionId1 shouldBe subscription1.id

        And("The subscriber should be subscribed")
        subscription1.isUnsubscribed shouldBe false

        And("The context should acknowledge the subscription")
        Mockito.verify(handler).send(acknowledge(0L, subscriptionId1, None))

        When("A second client subscribes to a table")
        val subscription2 = new TestStateTableSubscription
        val key2 = randomKey()
        Mockito.when(cache.subscribe(any(), any())).thenReturn(subscription2)
        val subscriptionId2 = context.subscribeTo(key2, cache, 0L, None)

        Then("The subscription identifier should match the subscription")
        subscriptionId2 shouldBe subscription2.id

        And("The subscriber should be subscribed")
        subscription1.isUnsubscribed shouldBe false

        And("The context should acknowledge the subscription")
        Mockito.verify(handler).send(acknowledge(0L, subscriptionId2, None))

        When("Closing the context")
        Await.result(context.close(serverInitiated = true), timeout)

        Then("The context should send completed")
        Mockito.verify(handler).send(notifyCompleted(0L, subscriptionId1))
        Mockito.verify(handler).send(notifyCompleted(0L, subscriptionId2))
    }

    "Context" should "reuse subscriptions" in {
        Given("A client context")
        val handler = Mockito.mock(classOf[ClientHandler])
        Mockito.when(handler.send(any())).thenReturn(Future.successful(null))
        val context = new ClientContext(handler)

        When("A client subscribes to a table")
        val key = randomKey()
        val cache = Mockito.mock(classOf[StateTableCache])
        val subscription = new TestStateTableSubscription
        Mockito.when(cache.subscribe(any(), any())).thenReturn(subscription)
        Mockito.when(cache.dispatcher).thenReturn(ImmediateExecutionContext)
        val requestId1 = random.nextLong()
        val lastVersion1 = Some(random.nextLong())
        val subscriptionId1 = context.subscribeTo(key, cache, requestId1,
                                                  lastVersion1)

        Then("The subscription identifier should match the subscription")
        subscriptionId1 shouldBe subscription.id

        And("The subscriber should be subscribed")
        subscription.isUnsubscribed shouldBe false

        And("The context should acknowledge the subscription")
        Mockito.verify(handler).send(acknowledge(requestId1, subscriptionId1,
                                                 lastVersion1))

        When("Subscribing a second time")
        val requestId2 = random.nextLong()
        val lastVersion2 = Some(random.nextLong())
        val subscriptionId2 = context.subscribeTo(key, cache, requestId2,
                                                  lastVersion2)

        Then("The subscription identifier should be the same")
        subscriptionId1 shouldBe subscriptionId2

        And("The context should acknowledge the subscription")
        Mockito.verify(handler).send(acknowledge(requestId2, subscriptionId2,
                                                 lastVersion2))

        And("Refresh is called")
        subscription.lastRefresh shouldBe lastVersion2
    }

    "Context" should "handle race conditions to subscribe" in {
        Given("A client context")
        val handler = Mockito.mock(classOf[ClientHandler])
        Mockito.when(handler.send(any())).thenReturn(Future.successful(null))
        val context = new ClientContext(handler)

        When("Several clients subscribes to a table")
        val key = randomKey()
        val cache = Mockito.mock(classOf[StateTableCache])
        val subscription = new MultiStateTableSubscription
        Mockito.when(cache.subscribe(any(), any())).thenReturn(subscription)
        Mockito.when(cache.dispatcher).thenReturn(ImmediateExecutionContext)
        val requestId = random.nextLong()
        val lastVersion = Some(random.nextLong())

        val threads = for (index <- 0 until 10) yield {
            new Thread(makeRunnable {
                context.subscribeTo(key, cache, requestId, lastVersion)
            })
        }

        for (thread <- threads){
            thread.start()
        }

        for (thread <- threads) {
            thread.join()
        }

        Then("The context should have used an existing executor 9 times")
        subscription.refresh.get shouldBe 9
    }

}
