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

package org.midonet.cluster.services.state.client

import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

import com.google.protobuf.{ByteString, Message}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import io.netty.channel.nio.NioEventLoopGroup

import org.junit.runner.RunWith
import org.mockito.Matchers.any
import org.mockito.Mockito
import org.scalactic.source
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Informer, Matchers}
import org.slf4j.LoggerFactory

import rx.Observer

import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.rpc.State
import org.midonet.cluster.rpc.State.ProxyResponse.Notify.Update
import org.midonet.cluster.rpc.State.{ProxyRequest, ProxyResponse}
import org.midonet.cluster.services.discovery._
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState.{ConnectionState, Disconnected}
import org.midonet.util.MidonetEventually

class TestServerHandler(server: TestServer,
                        goodUUID: UUID) extends Observer[Message] {
    val subscriptionId = new AtomicInteger(0)
    val hung = new AtomicBoolean(false)

    private val log = Logger(LoggerFactory.getLogger(classOf[TestServerHandler]))

    def hang(): Unit = { hung.set(true) }

    protected def onNext(msg: Message): Unit = {
        if (!hung.get) {
            msg match {
                case req: ProxyRequest => handleRequest(req)
                case _ =>
                    throw new Exception(s"Received unknown message type: $msg")
            }
        }
    }

    protected def onCompleted(): Unit = {
        log.info("Server handler session closed")
    }

    protected def onError(cause: Throwable): Unit = {
        log.error(s"Server handler received exception: $cause")
        throw cause
    }

    private def handleRequest(msg: ProxyRequest): Unit = {
        log.debug(s"Received message: $msg")
        msg.getDataCase match {
            case ProxyRequest.DataCase.SUBSCRIBE =>
                onSubscribe(msg.getRequestId,msg.getSubscribe)

            case ProxyRequest.DataCase.UNSUBSCRIBE =>
                onUnsubscribe(msg.getRequestId,msg.getUnsubscribe)

            case ProxyRequest.DataCase.PING =>
                onPing(msg.getRequestId,msg.getPing)

            case ProxyRequest.DataCase.DATA_NOT_SET =>
        }
    }

    private def onPing(requestId: Long, msg: ProxyRequest.Ping): Unit = {
        val response = ProxyResponse.newBuilder()
                .setRequestId(requestId)
                .setPong(ProxyResponse.Pong.getDefaultInstance)
                .build()
        server.write(response)
    }

    private def onSubscribe(requestId: Long,
                            msg: ProxyRequest.Subscribe): Unit = {

        val b = ProxyResponse.newBuilder().setRequestId(requestId)
        val uuid = new UUID(msg.getObjectId.getMsb,
                            msg.getObjectId.getLsb)

        if (uuid.compareTo(goodUUID) == 0) {
            val ack = ProxyResponse.Acknowledge.newBuilder()
                .setSubscriptionId(subscriptionId.incrementAndGet)
            server.write(b.setAcknowledge(ack).build())
        } else {
            val err = ProxyResponse.Error.newBuilder()
                        .setCode(ProxyResponse.Error.Code.INVALID_ARGUMENT)
                        .setDescription("Table doesn't exists")
            server.write(b.setError(err).build())
        }
    }

    protected def onUnsubscribe(requestId: Long,
                                msg: ProxyRequest.Unsubscribe): Unit = {
        val ack = ProxyResponse.Acknowledge.newBuilder()
            .setSubscriptionId(msg.getSubscriptionId)
        server.write(ProxyResponse.newBuilder().setRequestId(requestId)
                         .setAcknowledge(ack).build())
    }
}

@RunWith(classOf[JUnitRunner])
class StateProxyClientTest extends FeatureSpec
                                   with Matchers
                                   with GivenWhenThen
                                   with MidonetEventually {
    val log = LoggerFactory.getLogger(getClass)

    override val info = new Informer() {
        override def apply(message: String, payload: Option[Any] = None)
                          (implicit pos: source.Position): Unit = {
            log.info(message)
        }
    }

    val goodUUID = UUID.randomUUID()
    val existingTable = new StateSubscriptionKey(
        StateTable.Key(classOf[Int],
                       goodUUID,
                       classOf[Long],
                       classOf[String],
                       "test_table",
                       Nil),
        None)

    val missingTable = new StateSubscriptionKey(
        StateTable.Key(classOf[Int],
                       UUID.randomUUID(),
                       classOf[Long],
                       classOf[String],
                       "bad_table",
                       Nil),
        None)

    val executor = new ScheduledThreadPoolExecutor(2)
    executor.setMaximumPoolSize(2)

    implicit val exctx = ExecutionContext.fromExecutor(executor)

    class TestObjects(val softReconnectDelay: Duration = 200 milliseconds,
                      val maxAttempts: Int = 30,
                      val hardReconnectDelay: Duration = 1 second,
                      val connectTimeout: Duration = 2 seconds,
                      val readTimeout: Duration = 3 seconds) {

        val executor = new ScheduledThreadPoolExecutor(1)
        executor.setMaximumPoolSize(1)

        implicit val exctx = ExecutionContext.fromExecutor(executor)

        val numPoolThreads = 1
        val eventLoopGroup = new NioEventLoopGroup(numPoolThreads)

        val server = new TestServer(ProxyRequest.getDefaultInstance)
        val serverHandler = new TestServerHandler(server, goodUUID)
        server.attachedObserver = serverHandler

        val STATE_PROXY_CFG_OBJECT = ConfigFactory.parseString(
            s"""
              |state_proxy.enabled=true
              |state_proxy.network_threads=2
              |state_proxy.soft_reconnect_delay=${softReconnectDelay.toMillis}ms
              |state_proxy.max_soft_reconnect_attempts=$maxAttempts
              |state_proxy.hard_reconnect_delay=${hardReconnectDelay.toMillis}ms
              |state_proxy.connect_timeout=${connectTimeout.toMillis}ms
              |state_proxy.read_timeout=${readTimeout.toMillis}ms
            """.stripMargin
        )

        val conf = new StateProxyClientConfig(STATE_PROXY_CFG_OBJECT)

        val discovery = new MockDiscoverySelector(List.fill(10)(server.address))
        val client = new StateProxyClient(conf,
                                          discovery,
                                          executor,
                                          eventLoopGroup)

        def close(): Unit = {
            try {
                client.stop()
                server.serverChannel.close().await()
                eventually {
                    server.hasClient shouldBe false
                }
                executor.shutdownNow()
                for (g <- List(server.workerGroup, server.bossGroup, eventLoopGroup)) {
                    g.shutdownGracefully(0,100,MILLISECONDS).await()
                }
            } catch {
                case NonFatal(_) =>
            }
        }
    }

    val entryBuilder = ProxyResponse.Notify.Entry.newBuilder()
    val updateBuilder = ProxyResponse.Notify.Update.newBuilder()
        .setType(ProxyResponse.Notify.Update.Type.RELATIVE)

    private def mkEntry(k: String, v: String): ProxyResponse.Notify.Entry = {
        val keyBuilder = State.KeyValue.newBuilder
        ProxyResponse.Notify.Entry.newBuilder()
            .setKey(keyBuilder.setDataVariable(ByteString.copyFrom(k.getBytes)).build())
            .setValue(keyBuilder.setDataVariable(ByteString.copyFrom(v.getBytes)).build())
            .build()
    }

    private def mkEntry(k: Int, v: Long): ProxyResponse.Notify.Entry = {
        val keyBuilder = State.KeyValue.newBuilder
        ProxyResponse.Notify.Entry.newBuilder()
            .setKey(keyBuilder.setData32(k).build())
            .setValue(keyBuilder.setData64(v).build())
            .build()
    }

    updateBuilder.addEntries(mkEntry("key1","value1"))
    updateBuilder.addEntries(mkEntry("key2","value2"))
    updateBuilder.addEntries(mkEntry(7,-123L))

    private def updateMsgForSubscription(id: Long) =
        ProxyResponse.newBuilder.setRequestId(1)
                        .setNotify(ProxyResponse.Notify.newBuilder()
                                       .setSubscriptionId(id)
                                       .setUpdate(updateBuilder)).build()

    private def terminationMsgForSubscription(id: Long) =
        ProxyResponse.newBuilder.setRequestId(1)
            .setNotify(ProxyResponse.Notify.newBuilder()
                           .setSubscriptionId(id)
                           .setCompleted(
                                ProxyResponse.Notify.Completed.getDefaultInstance
                            )).build()

    feature("lifetime is determined by start/stop") {

        scenario("subscription before start") {

            Given("A newly-created state client")
            val t = new TestObjects
            val client = t.client

            When("observable is requested while stopped")
            val observer = Mockito.mock(classOf[Observer[Update]])
            val observable = client.observable(existingTable)

            Then("subscription fails if not yet started")
            val subscription = observable.subscribe(observer)
            eventually {
                subscription.isUnsubscribed shouldBe true
            }
            Mockito.verify(observer).onError(
                any(classOf[StateProxyClient.SubscriptionFailedException]))

            t.close()
        }


        scenario("subscription after start") {

            Given("A newly-created state client")
            val t = new TestObjects
            val client = t.client

            When("observable is requested before started")
            val observer = Mockito.mock(classOf[Observer[Update]])
            val observable = client.observable(existingTable)

            And("it is started")
            client.start()

            eventually {
                client.isConnected shouldBe true
            }

            Then("subscription works after start")
            val subscription = observable.subscribe(observer)
            eventually {
                subscription.isUnsubscribed shouldBe false
                client.numActiveSubscriptions shouldBe 1
            }

            Mockito.verifyNoMoreInteractions(observer)

            t.close()
        }

        scenario("unsubscription after start") {

            Given("A newly-created state client")
            val t = new TestObjects
            val client = t.client

            When("observable is requested before started")
            val observer = Mockito.mock(classOf[Observer[Update]])
            val observable = client.observable(existingTable)

            And("it is started")
            client.start()

            And("an observer is subscribed")
            val subscription = observable.subscribe(observer)
            eventually {
                client.numActiveSubscriptions shouldBe 1
            }

            Then("unsubscription after start works")
            subscription.isUnsubscribed shouldBe false
            subscription.unsubscribe()
            subscription.isUnsubscribed shouldBe true

            And("No events are posted to the observer")
            Mockito.verifyNoMoreInteractions(observer)

            t.close()
        }

        scenario("subscription after stop") {
            Given("A newly-created state client")
            val t = new TestObjects
            val client = t.client

            When("observable is requested before started")
            val observer = Mockito.mock(classOf[Observer[Update]])
            val observable = client.observable(existingTable)

            And("it is started")
            client.start()
            eventually {
                client.isConnected shouldBe true
            }
            client.ping() shouldBe true

            When("the client is stopped")
            client.stop() shouldBe true

            Then("further subscriptions are not possible")
            val subscription = observable.subscribe(observer)
            eventually {
                subscription.isUnsubscribed shouldBe true
            }

            And("subscription failure is reported")
            Mockito.verify(observer).onError(
                any(classOf[StateProxyClient.SubscriptionFailedException]))

            t.close()
        }

        scenario("unsubscription after stop") {
            Given("A newly-created state client")
            val t = new TestObjects
            val client = t.client

            When("observable is requested before started")
            val observer = Mockito.mock(classOf[Observer[Update]])
            val observable = client.observable(existingTable)

            And("it is started")
            client.start()

            And("subscriptions are made")
            val subscription = client.observable(existingTable)
                .subscribe(observer)
            subscription.isUnsubscribed shouldBe false
            eventually {
                client.numActiveSubscriptions shouldBe 1
            }

            When("the client is stopped")
            client.stop() shouldBe true

            Then("existing subscriptions are automatically unsubscribed")
            eventually {
                subscription.isUnsubscribed shouldBe true
                client.numActiveSubscriptions shouldBe 0
            }

            And("Completion is reported to the observer")
            Mockito.verify(observer).onCompleted()
            Mockito.verifyNoMoreInteractions(observer)

            t.close()
        }
    }

    feature("subscribers are isolated from network changes") {

        scenario("connectivity failure is not propagated") {

            val t = new TestObjects

            Given("A working link to the state server")
            val client = t.client
            client.start()

            And("An observer subscribes")
            val observer = Mockito.mock(classOf[Observer[Update]])
            val subscription = client.observable(existingTable)
                .subscribe(observer)
            subscription.isUnsubscribed shouldBe false
            eventually {
                client.numActiveSubscriptions shouldBe 1
            }

            When("the link goes down")
            t.server.close()
            eventually {
                client.numActiveSubscriptions shouldBe 0
            }

            And("The link is reconnected")
            eventually {
                client.numActiveSubscriptions shouldBe 1
            }

            And("An event is generated")
            client.ping() shouldBe true
            val update = updateMsgForSubscription(2)
            t.server.write(update)
            t.server.write(terminationMsgForSubscription(2))

            Then("Events were propagated to the subscriber")
            eventually {
                Mockito.verify(observer).onNext(update.getNotify.getUpdate)
                Mockito.verify(observer).onCompleted()
            }

            t.close()
        }
    }

    feature("clients can unsubscribe while link is down") {
        scenario("unsubscribe while re-connecting") {
            val t = new TestObjects

            Given("A working link to the state server")
            val client = t.client
            client.start()
            eventually {
                t.server.hasClient shouldBe true
                client.isConnected shouldBe true
            }

            When("An observer subscribes")
            val observer = Mockito.mock(classOf[Observer[Update]])
            val subscription = client.observable(existingTable)
                .subscribe(observer)
            subscription.isUnsubscribed shouldBe false
            eventually {
                client.numActiveSubscriptions shouldBe 1
            }

            And("the link goes down")
            t.server.close()

            And("The client unsubscribes")
            subscription.unsubscribe()
            eventually {
                subscription.isUnsubscribed shouldBe true
                client.numActiveSubscriptions shouldBe 0
            }

            And("The link is reconnected")
            eventually {
                client.isConnected shouldBe true
            }

            client.numActiveSubscriptions shouldBe 0
            t.close()
        }
    }

    feature("clients can subscribe while link is down") {
        scenario("subscribe while disconnected") {
            val t = new TestObjects

            Given("A working link to the state server")
            val client = t.client
            client.start()
            eventually {
                t.server.hasClient shouldBe true
                t.client.isConnected shouldBe true
            }

            When("the link goes down")
            t.server.setOffline()

            And("An observer subscribes")
            val observer = Mockito.mock(classOf[Observer[Update]])
            val subscription = client.observable(existingTable)
                .subscribe(observer)
            subscription.isUnsubscribed shouldBe false

            And("The link is reconnected")
            t.server.setOnline()
            eventually {
                client.numActiveSubscriptions shouldBe 1
            }

            And("An event is generated")
            client.ping() shouldBe true
            val update = updateMsgForSubscription(1)
            t.server.write(update)

            Then("Events were propagated to the subscriber")
            eventually {
                Mockito.verify(observer).onNext(update.getNotify.getUpdate)
            }

            t.close()
        }
    }

    feature("subscription errors are propagated as onError events") {
        scenario("subscription to non-existing table") {
            val t = new TestObjects

            Given("A working link to the state server")
            val client = t.client
            client.start()
            eventually {
                t.server.hasClient shouldBe true
            }

            When("An observer subscribes to an unexsisting table")
            val observer = Mockito.mock(classOf[Observer[Update]])
            val subscription = client.observable(missingTable)
                .subscribe(observer)

            Then("Error event is propagated and subscription is terminated")
            eventually {
                Mockito.verify(observer).onError(
                    any(classOf[StateProxyClient.SubscriptionFailedException]))
                subscription.isUnsubscribed shouldBe true
            }

            t.close()
        }
    }

    feature("no race conditions on start/stop") {
        scenario("multiple start") {

            Given("A client")
            val t = new TestObjects
            val client = t.client

            When("start is invoked in parallel")
            val tries = 5
            val succeeded = new AtomicInteger(0)
            val failed = new AtomicInteger(0)

            val futures = (1 to tries).map { _ =>
                Future {
                    try {
                        client.start()
                        succeeded.incrementAndGet()
                    } catch {
                        case _: IllegalStateException =>
                            failed.incrementAndGet()
                        case err: Throwable =>
                            throw err
                    }
                }
            }

            Then("Only one call succeeds")
            Await.ready(Future.sequence(futures), Duration.Inf)
            succeeded.get shouldBe 1
            failed.get shouldBe (tries - 1)
            t.close()

        }

        scenario("multiple stop") {

            Given("A started client")
            val t = new TestObjects
            val client = t.client

            client.start()
            eventually {
                client.isConnected shouldBe true
            }

            When("stop is invoked in parallel")
            val tries = 5
            val succeeded = new AtomicInteger(0)
            val failed = new AtomicInteger(0)
            val exceptions = new AtomicInteger(0)
            val futures = (1 to tries).map { _ =>
                Future {
                    try {
                        if (client.stop()) {
                            succeeded.incrementAndGet()
                        }
                        else {
                            failed.incrementAndGet()
                        }
                    } catch {
                        case _: Throwable =>
                            exceptions.incrementAndGet()
                    }
                }
            }

            Then("Only one call succeeds")
            Await.ready(Future.sequence(futures), Duration.Inf)
            succeeded.get shouldBe 1
            exceptions.get shouldBe 0
            failed.get shouldBe (tries - 1)

            t.close()

        }

    }

    feature("observers completed after after retry limit reached") {

        scenario("reach attempt limit") {

            Given("a state proxy client and a subscriber")
            val t = new TestObjects(softReconnectDelay = 200 milliseconds,
                                    maxAttempts = 1,
                                    hardReconnectDelay = 1 second)
            t.client.start()
            val observer = Mockito.mock(classOf[Observer[Update]])
            val subscription = t.client
                .observable(existingTable)
                .subscribe(observer)
            eventually {
                t.client.isConnected shouldBe true
                t.server.hasClient shouldBe true
            }

            When("the server dies")

            val startTime = System.nanoTime()
            t.server.setOffline()

            eventually {
                t.client.isConnected shouldBe false
            }

            Then("the observer is completed after retries")
            eventually {
                Mockito.verify(observer).onCompleted()
            }

            val ratio = (System.nanoTime() - startTime).toDouble /
                        (t.softReconnectDelay.toNanos * t.maxAttempts)

            ratio should be >= 0.8

            And("further subscriptions are not allowed")
            val subscription2 = t.client
                .observable(existingTable)
                .subscribe(observer)
            eventually {
                subscription2.isUnsubscribed shouldBe true
            }
            Mockito.verify(observer).onError(
                any(classOf[StateProxyClient.SubscriptionFailedException]))

            t.close()
        }
    }

    feature("connection observable behavior") {

        import StateTableClient.ConnectionState._

        scenario("disconnected before start") {

            Given("a client")
            val t = new TestObjects(softReconnectDelay = 50 milliseconds,
                                    maxAttempts = 5,
                                    hardReconnectDelay = 150 milliseconds)

            When("An observer subscribes")
            val observer = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(observer).isUnsubscribed shouldBe false

            Then("a disconnected event is fired")
            Mockito.verify(observer).onNext(Disconnected)

            t.close()
        }

        scenario("connected after start") {

            Given("a client")
            val t = new TestObjects(
                softReconnectDelay = 50 milliseconds,
                maxAttempts = 5,
                hardReconnectDelay = 150 milliseconds)

            When("an observer subscribes")
            val observer = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(observer).isUnsubscribed shouldBe false

            Mockito.verify(observer).onNext(Disconnected)

            And("later the client is started")
            t.client.start()

            Then("a connected event is fired")
            Mockito.verify(observer).onNext(Connected)

            And("subsequent observers receive also a connected event")
            val observerB = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(observerB).isUnsubscribed shouldBe false

            Mockito.verify(observerB).onNext(Connected)
            Mockito.verifyNoMoreInteractions(observer)

            t.close()
        }

        scenario("keeps connected during transient failures") {

            Given("a started client")
            val t = new TestObjects(softReconnectDelay = 200 milliseconds,
                                    maxAttempts = 5,
                                    hardReconnectDelay = 500 milliseconds)
            t.client.start()

            And("an observer")
            val observer = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(observer).isUnsubscribed shouldBe false

            Mockito.verify(observer).onNext(Connected)

            When("connection failure happens")
            eventually {
                t.client.isConnected shouldBe true
                t.server.hasClient shouldBe true
            }
            t.server.close()

            eventually {
                t.client.isConnected shouldBe false
            }

            Then("no disconnected event is generated")
            Mockito.verifyNoMoreInteractions(observer)

            And("eventually the client connects again")
            eventually {
                t.client.isConnected shouldBe true
            }

            And("no event is ever generated")
            Mockito.verifyNoMoreInteractions(observer)

            t.close()
        }

        scenario("disconnected after retry limit") {

            Given("a started client")
            val t = new TestObjects(200 milliseconds,
                                    3,
                                    hardReconnectDelay = 1 second)
            t.client.start()

            And("an observer")
            val observer = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(observer).isUnsubscribed shouldBe false

            Mockito.verify(observer).onNext(Connected)

            When("permanent connection failure happens")
            eventually {
                t.server.hasClient shouldBe true
                t.client.isConnected shouldBe true
            }

            val startTime = System.nanoTime()
            t.server.setOffline()

            Then("disconnected event is generated after soft retries")
            eventually {
                Mockito.verify(observer).onNext(Disconnected)
            }

            // check that the event was received "more or less" after
            // the grace time. Can't be too precise here or test might fail
            // on a loaded system
            val ratio = (System.nanoTime() - startTime).toDouble /
                        (t.softReconnectDelay.toNanos * t.maxAttempts)
            ratio should be >= 0.8

            t.close()
        }

        scenario("connected when server comes back") {

            Given("a started client")
            val softDelay = 200 milliseconds
            val hardDelay = 500 milliseconds
            val t = new TestObjects(softDelay, 0, hardDelay)

            t.client.start()

            And("an observer")
            val observer = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(observer).isUnsubscribed shouldBe false

            eventually {
                Mockito.verify(observer).onNext(Connected)
            }

            When("connection failure happens")
            eventually {
                t.server.hasClient shouldBe true
            }

            val startTime = System.nanoTime()
            t.server.setOffline()

            eventually {
                Mockito.verify(observer).onNext(Disconnected)
            }

            t.server.setOnline()

            Then("It connects after the hard delay")
            eventually {
                Mockito.verify(observer, Mockito.times(2)).onNext(Connected)
            }
            val took = System.nanoTime() - startTime
            val ratio = took.toDouble / hardDelay.toNanos
            ratio should be >= 0.8
            t.close()
        }
        scenario("regression - close before connect callback") {

            Given("a started client")
            val softDelay = 200 milliseconds
            val hardDelay = 500 milliseconds
            val t = new TestObjects(softDelay, 0, hardDelay)

            @volatile var blocking = true
            @volatile var ready = false

            And("A busy execution context")
            Future{
                while (blocking) {
                    ready = true
                    Thread.sleep(100)
                }
            }(t.exctx)
            while (!ready) {blocking = true}

            t.client.start()

            And("an observer")
            val observer = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(observer).isUnsubscribed shouldBe false

            eventually {
                Mockito.verify(observer).onNext(Connected)
            }

            eventually {
                t.server.hasClient shouldBe true
            }

            When("Server closes the connection")
            t.server.setOffline()

            And("The execution context resumes after the close")
            Future {
                Thread.sleep(1000)
                blocking = false
            }

            Then("The client realises it's disconnected")

            eventually {
                Mockito.verify(observer).onNext(Disconnected)
            }

            t.server.setOnline()

            Then("And it reconnects after a while")
            eventually {
                Mockito.verify(observer, Mockito.times(2)).onNext(Connected)
            }
            t.close()
        }

        scenario("completed after stop") {

            Given("a client")
            val t = new TestObjects(softReconnectDelay = 50 milliseconds,
                                    maxAttempts = 3,
                                    hardReconnectDelay = 1 second)
            t.client.start()

            And("an observer")
            val observer = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(observer).isUnsubscribed shouldBe false

            Mockito.verify(observer).onNext(Connected)

            When("client is stopped")
            t.client.stop()

            Then("the observer is completed")
            eventually {
                Mockito.verify(observer).onCompleted()
            }

            And("subsequent observers are completed too")
            val observerB = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(observerB).isUnsubscribed shouldBe true

            Mockito.verify(observerB).onCompleted()

            t.close()
        }
    }

    feature("subscriptions are removed after table termination from server") {
        scenario("table completed") {

            Given("a client")
            val t = new TestObjects(softReconnectDelay = 50 milliseconds,
                                    maxAttempts = 3,
                                    hardReconnectDelay = 1 second)
            t.client.start()

            And("an observer")
            val observer = Mockito.mock(classOf[Observer[Update]])
            val subscription = t.client.observable(existingTable).subscribe(observer)
            subscription.isUnsubscribed shouldBe false

            eventually {
                t.client.numActiveSubscriptions shouldBe 1
            }

            When("The server terminates the table subscription")
            t.server.write(terminationMsgForSubscription(1))

            Then("The observer is completed")
            eventually {
                Mockito.verify(observer).onCompleted()
            }

            And("The client is not handling the subscription anymore")
            t.client.numActiveSubscriptions shouldBe 0

            t.close()
        }
    }

    feature("connection timeout") {
        scenario("connection timeout works") {

            val badServer = MidonetServiceHostAndPort("192.0.2.0", 19)
            val softDelay = 200 milliseconds
            val hardDelay = 500 milliseconds
            val connectTimeout = 2 seconds

            Given("a client")
            val t = new TestObjects(softDelay, 0, hardDelay,
                                    connectTimeout)
            t.discovery.clear()
            t.discovery.prependInstance(badServer)

            val startTime = System.nanoTime()
            t.client.start()

            And("an observer")
            val obs = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(obs).isUnsubscribed shouldBe false

            eventually {
                Mockito.verify(obs).onNext(Disconnected)
            }

            val took = System.nanoTime() - startTime
            val expected = connectTimeout.toNanos
            val diff = Math.abs(took - expected)
            diff should be <= connectTimeout.toNanos
            t.close()
        }

        scenario("Uses the next server if failed") {

            val badServer = MidonetServiceHostAndPort("192.0.2.0", 19)
            val softDelay = 200 milliseconds
            val hardDelay = 500 milliseconds
            val connectTimeout = 2 seconds

            Given("a client")
            val t = new TestObjects(softDelay, 0, hardDelay,
                                    connectTimeout)
            t.discovery.prependInstance(badServer)

            val startTime = System.nanoTime()
            t.client.start()

            And("an observer")
            val obs = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(obs).isUnsubscribed shouldBe false

            eventually {
                t.server.hasClient shouldBe true
            }

            val took = System.nanoTime() - startTime
            val expected = connectTimeout.toNanos + hardDelay.toNanos
            val diff = Math.abs(took - expected)
            diff should be <= connectTimeout.toNanos

            t.close()
        }
    }

    feature("Failure detection") {
        val readTimeout = 3.seconds
        scenario(s"Client fails over within $readTimeout, if a server hangs") {
            Given("a client")
            val t = new TestObjects(readTimeout=readTimeout)

            And("2 servers")
            val server2 = new TestServer(ProxyRequest.getDefaultInstance)
            val serverHandler2 = new TestServerHandler(server2, goodUUID)
            server2.attachedObserver = serverHandler2

            t.discovery.prependInstance(server2.address)

            When("the client is started")
            val startTime = System.nanoTime()
            t.client.start()

            val obs = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(obs).isUnsubscribed shouldBe false

            Then("it should connect to a server")
            eventually {
                t.client.currentAddress shouldBe Some(server2.address)
                t.client.isConnected shouldBe true
            }

            When("that server hangs")
            serverHandler2.hang()

            Then("the client should disconnect, and reconnect to another server")
            eventually(timeout(readTimeout*1.5)) {
                t.client.currentAddress shouldBe Some(t.server.address)
                t.client.isConnected shouldBe true
            }

            And("this should take no longer than 10 seconds")
            val took = System.nanoTime - startTime
            took should be <= (readTimeout + (readTimeout/4)).toNanos
            t.close()
        }

        scenario("Client doesn't fail over, if server is just quiet") {
            Given("a client")
            val t = new TestObjects(readTimeout=readTimeout)

            And("2 servers")
            val server2 = new TestServer(ProxyRequest.getDefaultInstance)
            val serverHandler2 = new TestServerHandler(server2, goodUUID)
            server2.attachedObserver = serverHandler2

            t.discovery.prependInstance(server2.address)

            When("the client is started")
            val startTime = System.nanoTime()
            t.client.start()

            val obs = Mockito.mock(classOf[Observer[ConnectionState]])
            t.client.connection.subscribe(obs).isUnsubscribed shouldBe false

            Then("it should connect to a server")
            eventually {
                t.client.currentAddress shouldBe Some(server2.address)
                t.client.isConnected shouldBe true
            }

            When("that server says nothing")

            Then("the client should stay connected")
            Thread.sleep((readTimeout*1.5).toMillis)

            t.client.currentAddress shouldBe Some(server2.address)
            t.client.isConnected shouldBe true
        }
    }
}
