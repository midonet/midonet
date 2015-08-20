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

package org.midonet.vtep

import java.net.ConnectException
import java.util
import java.util.concurrent.{Executor, ExecutorService, ScheduledThreadPoolExecutor, TimeUnit}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

import com.google.common.util.concurrent.ListenableFuture

import io.netty.channel.{Channel, ChannelFuture, DefaultChannelPromise}
import io.netty.util.concurrent.ImmediateEventExecutor

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.data.vtep.VtepConnection.ConnectionState._
import org.midonet.cluster.data.vtep.{VtepStateException, VtepNotConnectedException}
import org.midonet.cluster.data.vtep.model.VtepEndPoint
import org.midonet.util.concurrent._
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class OvsdbVtepConnectionTest extends FeatureSpec with Matchers
                              with BeforeAndAfter with GivenWhenThen {

    private val timeout = 5 seconds
    private val endpoint = VtepEndPoint("127.0.0.1", 6632)

    private val dbNameList =
        util.Collections.singletonList(OvsdbTools.DB_HARDWARE_VTEP)
    private val futureDbList =
        new MockListenableFuture[util.List[String]](dbNameList)

    private var vtepExecutor: ScheduledThreadPoolExecutor = _
    private implicit var vtepContext: ExecutionContext = _
    private var channel: Channel = _
    private var closeFuture: DefaultChannelPromise = _

    type VtepObserver = TestAwaitableObserver[State]

    class MockListenableFuture[V](v: V) extends ListenableFuture[V] {
        override def cancel(b: Boolean) = false
        override def isCancelled = false
        override def get = v
        override def get(l: Long, u: TimeUnit) = v
        override def isDone = true
        override def addListener(listener: Runnable, executor: Executor) =
            executor.execute(listener)
    }

    def newMockDbSchema = Mockito.mock(classOf[DatabaseSchema])
    def newMockClient = {
        val client = Mockito.mock(classOf[OvsdbClient])
        val futureDb = new MockListenableFuture[DatabaseSchema](newMockDbSchema)
        Mockito.when(client.getDatabases).thenReturn(futureDbList)
        Mockito.when(client.getSchema(OvsdbTools.DB_HARDWARE_VTEP))
            .thenReturn(futureDb)
        client
    }

    def awaitState(vtep: OvsdbVtepConnection, st: State, t: Duration): State =
        vtep.awaitState(Set(st), t)

    class TestableConnection(override val endPoint: VtepEndPoint,
                             vtepExecutor: ExecutorService,
                             retryInterval: Duration, maxRetries: Long,
                             channel: Channel = null,
                             client: OvsdbClient = null)
        extends OvsdbVtepConnection(endPoint, vtepExecutor, retryInterval,
                                    maxRetries) {
        def break(future: ChannelFuture): Unit = {
            newCloseListener(channel, client).operationComplete(future)
        }
        protected override def openChannel(): Future[Channel] = {
            Future.successful(channel)
        }
        protected override def initializeClient(channel: Channel)
        : (Channel, OvsdbClient) = {
            (channel, client)
        }
    }

    before {
        vtepExecutor = new ScheduledThreadPoolExecutor(1)
        vtepContext = ExecutionContext.fromExecutor(vtepExecutor)
        channel = Mockito.mock(classOf[Channel])
        closeFuture = new DefaultChannelPromise(channel,
                                                ImmediateEventExecutor.INSTANCE)

        Mockito.when(channel.closeFuture()).thenReturn(closeFuture)
        Mockito.when(channel.close()).thenReturn(closeFuture)
    }

    after {
        vtepExecutor.shutdown()
        if (!vtepExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
            vtepExecutor.shutdownNow()
            vtepExecutor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    feature("VTEP connection") {
        scenario("Check the initial state") {
            Given("A VTEP connection")
            val vtep = new TestableConnection(endpoint, vtepExecutor, 0 seconds,
                                              1)

            Then("The VTEP should be disconnecyed")
            vtep.getState shouldBe Disconnected

            And("Wating for disconnected state should complete")
            awaitState(vtep, Disconnected, timeout) shouldBe Disconnected

            vtep.close().await(timeout)
        }

        scenario("Connect to the VTEP") {
            Given("A VTEP client")
            val client = newMockClient

            And("A VTEP connection")
            val vtep = new TestableConnection(endpoint, vtepExecutor, 0 seconds,
                                              1, channel, client)

            And("A VTEP connection observer")
            val observer = new VtepObserver
            val subscription = vtep.observable.subscribe(observer)

            When("Connecting to the VTEP")
            val connected = vtep.connect()

            Then("The connection state should be ready")
            awaitState(vtep, Ready, timeout) shouldBe Ready

            And("The future should be completed")
            connected.await(timeout) shouldBe Ready

            And("The observer should have received three notifications")
            observer.awaitOnNext(4, timeout) shouldBe true
            observer.getOnNextEvents should contain theSameElementsInOrderAs Vector(
                Disconnected, Connecting, Connected, Ready)

            And("The connection should have initialized the NETTY channel")
            Mockito.verify(channel, Mockito.times(1)).closeFuture()
            Mockito.verifyNoMoreInteractions(channel)

            And("The connection should have fetched the VTEP schema")
            Mockito.verify(client, Mockito.times(1)).getDatabases
            Mockito.verify(client, Mockito.times(1))
                .getSchema(OvsdbTools.DB_HARDWARE_VTEP)
            Mockito.verifyNoMoreInteractions(client)

            subscription.unsubscribe()
            closeFuture.setSuccess()
            vtep.close().await(timeout)
        }

        scenario("Disconnect from the VTEP") {
            Given("A VTEP client")
            val client = newMockClient

            And("A VTEP connection")
            val vtep = new TestableConnection(endpoint, vtepExecutor, 0 seconds,
                                              1, channel, client)

            And("A VTEP connection observer")
            val observer = new VtepObserver
            val subscription = vtep.observable.subscribe(observer)

            When("Connecting to the VTEP")
            val connected = vtep.connect()

            Then("The connection should be ready")
            awaitState(vtep, Ready, timeout) shouldBe Ready
            observer.awaitOnNext(4, timeout) shouldBe true

            And("The future should be completed")
            connected.await(timeout) shouldBe Ready

            When("Disconnecting to the VTEP")
            val disconnected = vtep.disconnect()
            awaitState(vtep, Disconnecting, timeout) shouldBe Disconnecting
            observer.awaitOnNext(5, timeout) shouldBe true

            And("Emulating the channel close event")
            closeFuture.setSuccess()

            Then("The state should be disconnected")
            awaitState(vtep, Disconnected, timeout) shouldBe Disconnected

            And("The future should be completed")
            disconnected.await(timeout) shouldBe Disconnected

            And("The observer should have received all notifications")
            observer.awaitOnNext(6, timeout) shouldBe true
            observer.getOnNextEvents should contain theSameElementsInOrderAs Vector(
                Disconnected, Connecting, Connected, Ready, Disconnecting,
                Disconnected)

            And("The connection should have closed the NETTY channel")
            Mockito.verify(channel, Mockito.times(1)).closeFuture()
            Mockito.verify(channel, Mockito.times(1)).close()
            Mockito.verifyNoMoreInteractions(channel)

            And("The connection should have fetched the VTEP schema")
            Mockito.verify(client, Mockito.times(1)).getDatabases
            Mockito.verify(client, Mockito.times(1))
                .getSchema(OvsdbTools.DB_HARDWARE_VTEP)
            Mockito.verifyNoMoreInteractions(client)

            subscription.unsubscribe()
            vtep.close().await(timeout)
        }

        scenario("Automatic reconnection") {
            Given("A VTEP client")
            val client = newMockClient

            And("A VTEP connection")
            val vtep = new TestableConnection(endpoint, vtepExecutor, 0 seconds,
                                              1, channel, client)

            And("A VTEP connection observer")
            val observer = new VtepObserver
            val subscription = vtep.observable.subscribe(observer)

            When("Connecting to the VTEP")
            val connected = vtep.connect()

            Then("The connection should be ready")
            awaitState(vtep, Ready, timeout) shouldBe Ready
            observer.awaitOnNext(4, timeout) shouldBe true

            And("The future should be completed")
            connected.await(timeout) shouldBe Ready

            When("The connection becomes broken")
            vtep.break(new DefaultChannelPromise(channel,
                                                 ImmediateEventExecutor.INSTANCE))

            Then("The connection should re-establish")
            observer.awaitOnNext(8, timeout) shouldBe true
            awaitState(vtep, Ready, timeout) shouldBe Ready
            observer.getOnNextEvents should contain theSameElementsInOrderAs Vector(
                Disconnected, Connecting, Connected, Ready, Disconnected,
                Connecting, Connected, Ready)

            And("The connection should have closed the NETTY channel")
            Mockito.verify(channel, Mockito.times(2)).closeFuture()
            Mockito.verifyNoMoreInteractions(channel)

            And("The connection should have fetched the VTEP schema")
            Mockito.verify(client, Mockito.times(2)).getDatabases
            Mockito.verify(client, Mockito.times(2))
                .getSchema(OvsdbTools.DB_HARDWARE_VTEP)
            Mockito.verifyNoMoreInteractions(client)

            subscription.unsubscribe()
            closeFuture.setSuccess()
            vtep.close().await(timeout)
        }

        scenario("Bad connection") {
            Given("A VTEP client")
            val client = newMockClient

            And("A VTEP connection")
            val vtep = new TestableConnection(endpoint, vtepExecutor, 0 seconds,
                                              0, channel, client) {
                protected override def openChannel(): Future[Channel] = {
                    Future.failed(new ConnectException())
                }
            }

            And("A VTEP connection observer")
            val observer = new VtepObserver
            val subscription = vtep.observable.subscribe(observer)

            When("Connecting to the VTEP")
            val connected = vtep.connect()

            Then("The connection should be failed")
            awaitState(vtep, Failed, timeout) shouldBe Failed
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents should contain theSameElementsInOrderAs
                List(Disconnected, Connecting, Failed)

            And("The future should have failed")
            intercept[VtepNotConnectedException] {
                connected.await(timeout)
            }

            subscription.unsubscribe()
            closeFuture.setSuccess()
            vtep.close().await(timeout)
        }

        scenario("VTEP disconnects on close") {
            Given("A VTEP client")
            val client = newMockClient

            And("A VTEP connection")
            val vtep = new TestableConnection(endpoint, vtepExecutor, 0 seconds,
                                              1, channel, client)

            And("A VTEP connection observer")
            val observer = new VtepObserver
            val subscription = vtep.observable.subscribe(observer)

            When("Connecting to the VTEP")
            val connected = vtep.connect()

            Then("The connection should be ready")
            awaitState(vtep, Ready, timeout) shouldBe Ready
            observer.awaitOnNext(4, timeout) shouldBe true

            And("The future should be completed")
            connected.await(timeout) shouldBe Ready

            When("Closing the VTEP connection")
            val closed = vtep.close()
            awaitState(vtep, Disconnecting, timeout) shouldBe Disconnecting
            observer.awaitOnNext(5, timeout) shouldBe true

            And("Emulating the channel close event")
            closeFuture.setSuccess()

            Then("The state should be disposed")
            awaitState(vtep, Disposed, timeout) shouldBe Disposed

            And("The observer should have received all notifications")
            observer.awaitOnNext(7, timeout) shouldBe true
            observer.getOnNextEvents should contain theSameElementsInOrderAs Vector(
                Disconnected, Connecting, Connected, Ready, Disconnecting,
                Disconnected, Disposed)

            And("The closed future should be completed")
            closed.await(timeout) shouldBe Disposed

            And("The connection should have closed the NETTY channel")
            Mockito.verify(channel, Mockito.times(1)).closeFuture()
            Mockito.verify(channel, Mockito.times(1)).close()
            Mockito.verifyNoMoreInteractions(channel)

            And("The connection should have fetched the VTEP schema")
            Mockito.verify(client, Mockito.times(1)).getDatabases
            Mockito.verify(client, Mockito.times(1))
                .getSchema(OvsdbTools.DB_HARDWARE_VTEP)
            Mockito.verifyNoMoreInteractions(client)

            subscription.unsubscribe()
            vtep.close().await(timeout)
        }

        scenario("VTEP connection always fails when disposed") {
            Given("A VTEP client")
            val client = newMockClient

            And("A VTEP connection")
            val vtep = new TestableConnection(endpoint, vtepExecutor, 0 seconds,
                                              1, channel, client)

            And("A VTEP connection observer")
            val observer = new VtepObserver
            val subscription = vtep.observable.subscribe(observer)

            When("The connection is closed")
            vtep.close().await(timeout) shouldBe Disposed

            Then("The observer should have received all notifications")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents should contain theSameElementsInOrderAs Vector(
                Disconnected, Disposed)

            And("Connecting should fail")
            intercept[VtepStateException] {
                vtep.connect().await(timeout)
            }

            And("Disconnecting should fail")
            intercept[VtepStateException] {
                vtep.disconnect().await(timeout)
            }
        }
    }
}
