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

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent._
import scala.concurrent.duration._

import com.google.protobuf.Message

import io.netty.channel.nio.NioEventLoopGroup

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import rx.Observer

import org.midonet.cluster.rpc.State.{ProxyRequest, ProxyResponse}
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class ConnectionTest extends FeatureSpec
                             with Matchers
                             with GivenWhenThen
                             with MidonetEventually {

    type ProxyConnection = Connection[ProxyRequest,ProxyResponse]

    val numThreads = 4
    val executor = Executors.newFixedThreadPool(numThreads)
    implicit val exctx = ExecutionContext.fromExecutor(executor)

    // not used as the event loop from Netty is simulated
    implicit val eventLoopGroup = new NioEventLoopGroup(numThreads)

    class CountingObserver[T <: Message] extends Observer[T] {

        val current = new AtomicInteger(0)
        val totalCounter = new AtomicInteger(0)
        val closePromise = Promise[Unit]

        override def onCompleted(): Unit = {
            closePromise.trySuccess(())
        }

        override def onError(cause: Throwable): Unit = {
            closePromise.tryFailure(cause)
        }

        override def onNext(msg: T): Unit = {
            totalCounter.incrementAndGet()
            msg match {
                case req: ProxyRequest =>
                    val wanted = current.incrementAndGet()
                    val has = req.getRequestId
                    if (has != wanted) {
                        onError(new Exception(s"Out of place request. has:$has wanted: $wanted"))
                    }
                case req: ProxyResponse =>
                    val wanted = current.incrementAndGet()
                    val has = req.getRequestId
                    if (has != wanted) {
                        onError(new Exception(s"Out of place response. has:$has wanted: $wanted"))
                    }
                case _ => onError(new Exception("Unexpected message"))
            }
        }

        def numReceived: Int = totalCounter.get
    }

    val testMsgBuilder = ProxyRequest.newBuilder()


    val exception = new Exception("Test exception")
    val connectionDelay = 50 millis

    private def msgId(id: Int) = testMsgBuilder.setRequestId(id)
        .setPing(ProxyRequest.Ping.getDefaultInstance)
        .build()

    val host = "localhost"
    val msg = msgId(33)

    val decoder = ProxyResponse.getDefaultInstance

    feature("Disconnected behavior") {
        val observer = Mockito.mock(classOf[Observer[ProxyResponse]])
        val serverObserverMock = Mockito.mock(classOf[Observer[Message]])
        val server = new TestServer(ProxyRequest.getDefaultInstance)
        server.attachedObserver = serverObserverMock

        scenario("Disconnection is reported") {
            Given("A freshly-created connection")
            val client = new ProxyConnection(host,0,observer,decoder)

            When("Connection status is queried")
            val isConnected = client.isConnected

            Then("State is disconnected")
            assert(!isConnected)

            And("No events are passed to the observer")
            Mockito.verifyZeroInteractions(observer)
        }

        scenario("I/O operations report failure") {
            Given("A freshly-created connection")
            val client = new ProxyConnection(host,0,observer,decoder)

            Then("Flush reports failure")
            assert(!client.flush())

            And("Write reports failure when flushing")
            assert(!client.write(msg,flush=true))

            And("Write reports failure when buffering")
            assert(!client.write(msg,flush=false))
        }
    }
    feature("Connection behavior") {
        val observer = Mockito.mock(classOf[Observer[ProxyResponse]])
        val serverObserverMock = Mockito.mock(classOf[Observer[Message]])
        val server = new TestServer(ProxyRequest.getDefaultInstance)
        server.attachedObserver = serverObserverMock

        scenario("Simple connection works") {

            Given("A connection to a local server")
            val client = new ProxyConnection(host, server.address.port,
                                             observer, decoder)

            When("An asynchronous connection is triggered")
            val future = client.connect()

            Then("The connection succeeds within a second")
            Await.result(future,1 second)

            And("has connected state")
            assert(client.isConnected)

            And("can be closed")
            client.close()
        }

        scenario("Failed connection fails") {

            Given("A connection to an invalid address")
            val client = new ProxyConnection(host,0,observer,decoder)

            When("An asynchronous connection is triggered")
            val future = client.connect()

            Then("The connection attempt terminates within a second")
            assert(Await.ready(future.failed,1 second).isCompleted)

            And("Results in an error")
            ScalaFutures.whenReady(future.failed) {
                err => err shouldBe a[Exception]
            }

            And("has disconnected state")
            assert(!client.isConnected)
        }

        scenario("Early close invalidates connection") {

            Given("A connection to a local server")
            val client = new ProxyConnection(host, server.address.port,
                                             observer, decoder)
            When("The connection is closed before connecting")
            client.close()

            Then("a connection attempt terminates within a second")
            val future = client.connect()
            assert(Await.ready(future.failed,1 second).isCompleted)

            And("Results in an illegal state exception")
            ScalaFutures.whenReady(future.failed) {
                err => err shouldBe a[IllegalStateException]
            }

            And("has disconnected state")
            assert(!client.isConnected)
        }

        scenario("Can't be reconnected") {

            Given("A connection to a local server")
            val client = new ProxyConnection(host, server.address.port,
                                             observer, decoder)

            When("The connection is successful")
            Await.result(client.connect(),1 second)
            assert(client.isConnected)

            And("The connection is closed")
            client.close()

            Then("a second connection attempt fails")
            val future = client.connect()
            assert(Await.ready(future.failed,1 second).isCompleted)

            And("Results in an illegal state exception")
            ScalaFutures.whenReady(future.failed) {
                err => err shouldBe a[IllegalStateException]
            }

            And("has disconnected state")
            assert(!client.isConnected)
        }
    }

    feature("Message delivery") {
        scenario("Messages are received on the other end") {

            Given("A connection to a local server")
            val observer = Mockito.mock(classOf[Observer[ProxyResponse]])
            val serverObserverMock = Mockito.mock(classOf[Observer[Message]])
            val server = new TestServer(ProxyRequest.getDefaultInstance)
            server.attachedObserver = serverObserverMock
            val client = new ProxyConnection(host, server.address.port,
                                             observer, decoder)

            When("An asynchronous connection is triggered")
            val future = client.connect()

            Then("The connection succeeds within a second")
            Await.result(future,1 second)
            assert(client.isConnected)

            And("A Ping message is sent")
            val result = client.write(msg)
            Then("The delivery succeeds")
            assert(result)

            And("The message is eventually received at the other end")
            Mockito.verify(serverObserverMock,
                           Mockito.timeout(1000)).onNext(msg)

            And("The client can be closed")
            client.close()
        }
    }
    feature("Message delivery ordering") {
        def testWriteOrdering(count: Int, flush: Boolean): Unit = {

            Given("A connection to a local server")
            val observer = Mockito.mock(classOf[Observer[ProxyResponse]])
            val server = new TestServer(ProxyRequest.getDefaultInstance)
            val client = new ProxyConnection(host, server.address.port,
                                             observer, decoder)

            And("a counting observer")
            val countingObserver = new CountingObserver[Message]
            server.attachedObserver = countingObserver

            When("An asynchronous connection is triggered")
            val future = client.connect()

            Then("The connection succeeds within a second")
            Await.result(future,1 second)
            assert(client.isConnected)

            And("Sequential messages are sent")
            for (i <- 1 to count) {
                val m = msgId(i)
                assert(client.write(m,flush))
            }
            if (!flush) client.flush()

            And("The client can be closed")
            client.close()

            And("Eventually the server-side closes successfully")
            Await.result(countingObserver.closePromise.future,5 seconds)
        }
        scenario("Messages are received in the same order they were sent - flushing") {
            testWriteOrdering(100,flush=true)
        }
        scenario("Messages are received in the same order they were sent - non flushing") {
            testWriteOrdering(100,flush=false)
        }
    }

    feature("Observer") {
        scenario("Observable is completed when remote closes") {

            Given("A connection to a local server")
            val observer = Mockito.mock(classOf[Observer[ProxyResponse]])
            val serverObserverMock = Mockito.mock(classOf[Observer[Message]])
            val server = new TestServer(ProxyRequest.getDefaultInstance)
            server.attachedObserver = serverObserverMock
            val client = new ProxyConnection(host, server.address.port,
                                             observer, decoder)

            When("An asynchronous connection is triggered")
            val future = client.connect()

            And("The connection succeeds within a second")
            Await.result(future,1 second)
            assert(client.isConnected)

            And("The remote end gets connected")
            eventually { server.hasClient shouldBe true }

            And("Remote end is closed")
            server.close()

            Then("The observer is eventually completed")
            Mockito.verify(observer,
                           Mockito.timeout(1000)).onCompleted()
        }

        scenario("Messages are observed in order") {

            Given("A connection to a local server")
            val serverObserverMock = Mockito.mock(classOf[Observer[Message]])
            val server = new TestServer(ProxyRequest.getDefaultInstance)
            val countingObserver = new CountingObserver[ProxyResponse]
            val client = new ProxyConnection(host,
                                             server.address.port,
                                             countingObserver,
                                             decoder)
            server.attachedObserver = serverObserverMock

            When("An asynchronous connection is triggered")
            val future = client.connect()

            Then("The connection succeeds within a second")
            Await.result(future,5 seconds)
            assert(client.isConnected)
            eventually { server.hasClient shouldBe true }

            And("Sequential messages are sent")
            val count = 100
            for (i <- 1 to count) {
                val m = msgId(i)
                server.write(m)
            }

            And("The messages are eventually received")
            eventually {
                countingObserver.numReceived shouldBe count
            }

            And("The connection is closed")
            server.close()
            Await.result(countingObserver.closePromise.future,1 second)
            ScalaFutures.whenReady(countingObserver.closePromise.future) {
                case _ =>
            }
        }
    }
}
