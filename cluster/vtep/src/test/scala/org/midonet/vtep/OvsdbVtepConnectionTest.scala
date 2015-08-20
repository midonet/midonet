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

import java.net.InetAddress
import java.util
import java.util.concurrent.{ExecutorService, Executor, ScheduledThreadPoolExecutor, TimeUnit}

import scala.collection.JavaConversions._
import scala.concurrent.duration.{Duration, _}

import com.google.common.util.concurrent.ListenableFuture

import org.junit.runner.RunWith
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{Matchers => MockitoMatchers, Mockito}
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.opendaylight.ovsdb.lib.{OvsdbClient, OvsdbConnection, OvsdbConnectionListener}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.data.vtep.VtepConnection.ConnectionState._
import org.midonet.cluster.data.vtep.model.VtepEndPoint
import org.midonet.util.reactivex.TestAwaitableObserver

//@RunWith(classOf[JUnitRunner])
class OvsdbVtepConnectionTest extends FeatureSpec
                                      with Matchers
                                      with BeforeAndAfter {

    val timeout = Duration(5, TimeUnit.SECONDS)

    val endpoint = VtepEndPoint("127.0.0.1", 6632)
    val dbNameList =
        util.Collections.singletonList(OvsdbTools.DB_HARDWARE_VTEP)
    val futureDbList =
        new MockListenableFuture[util.List[String]](dbNameList)

    var vtepThread: ScheduledThreadPoolExecutor = _

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
                             retryInterval: Duration, maxRetries: Long)
        extends OvsdbVtepConnection(endPoint, vtepExecutor, retryInterval,
                                    maxRetries) {

    }

    before {
        vtepThread = new ScheduledThreadPoolExecutor(1)
    }

    after {
        vtepThread.shutdown()
        if (!vtepThread.awaitTermination(2, TimeUnit.SECONDS)) {
            vtepThread.shutdownNow()
            vtepThread.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    feature("VTEP connection") {

        scenario("check initial state") {
            val vtep = new TestableConnection(endpoint, vtepThread, 0 seconds, 1)
            vtep.getState shouldBe Disconnected
            awaitState(vtep, Disconnected, timeout) shouldBe Disconnected
        }

        scenario("connect to vtep") {
            val client = newMockClient

            val vtep = new TestableConnection(endpoint, vtepThread, 0 seconds, 1)

            val monitor = new VtepObserver
            val subscription = vtep.observable.subscribe(monitor)

            vtep.connect()
            awaitState(vtep, Ready, timeout) shouldBe Ready
            monitor.awaitOnNext(3, timeout) shouldBe true

            val events = monitor.getOnNextEvents.toList
            events shouldBe List(Disconnected, Connected, Ready)

            //Mockito.verify(connectionService, Mockito.times(1))
            //    .registerConnectionListener(
            //        MockitoMatchers.any[OvsdbConnectionListener])
            //Mockito.verify(connectionService, Mockito.times(1))
            //    .connect(MockitoMatchers.any[InetAddress](),
            //             MockitoMatchers.anyInt())
            //Mockito.verifyNoMoreInteractions(connectionService)

            Mockito.verify(client, Mockito.times(1)).getDatabases
            Mockito.verify(client, Mockito.times(1))
                .getSchema(OvsdbTools.DB_HARDWARE_VTEP)
            Mockito.verifyNoMoreInteractions(client)
            subscription.unsubscribe()
        }

        scenario("disconnect from vtep") {
            val client = newMockClient
            //Mockito
            //    .when(connectionService.connect(
            //    MockitoMatchers.any[InetAddress](),
            //    MockitoMatchers.anyInt()))
             //   .thenReturn(client)

            val vtep = new TestableConnection(endpoint, vtepThread, 0 seconds, 1)

            val monitor = new VtepObserver
            val subscription = vtep.observable.subscribe(monitor)

            vtep.connect()
            awaitState(vtep, Ready, timeout) shouldBe Ready
            monitor.awaitOnNext(3, timeout) shouldBe true

            vtep.disconnect()
            awaitState(vtep, Disconnecting, timeout) shouldBe Disconnecting
            monitor.awaitOnNext(4, timeout) shouldBe true

            // emulate async close event
            //listeners.foreach(_.disconnected(client))

            awaitState(vtep, Disconnected, timeout) shouldBe Disconnected
            monitor.awaitOnNext(5, timeout) shouldBe true

            val events = monitor.getOnNextEvents.toList
            events shouldBe
                List(Disconnected, Connected, Ready, Disconnecting, Disconnected)

            //Mockito.verify(connectionService, Mockito.times(1))
            //    .registerConnectionListener(
            //        MockitoMatchers.any[OvsdbConnectionListener])
            //Mockito.verify(connectionService, Mockito.times(1))
            //    .connect(MockitoMatchers.any[InetAddress](),
            //             MockitoMatchers.anyInt())
            //Mockito.verify(connectionService, Mockito.times(1))
            //    .disconnect(client)
            //Mockito.verifyNoMoreInteractions(connectionService)

            Mockito.verify(client, Mockito.times(1)).getDatabases
            Mockito.verify(client, Mockito.times(1))
                .getSchema(OvsdbTools.DB_HARDWARE_VTEP)
            Mockito.verifyNoMoreInteractions(client)
            subscription.unsubscribe()
        }

        scenario("automatic reconnection") {
            val client = newMockClient
            //Mockito
            //    .when(connectionService.connect(
            //    MockitoMatchers.any[InetAddress](),
            //    MockitoMatchers.anyInt()))
            //    .thenReturn(client)

            val vtep = new TestableConnection(endpoint, vtepThread, 0 seconds, 1)

            val monitor = new VtepObserver
            val subscription = vtep.observable.subscribe(monitor)

            vtep.connect()
            awaitState(vtep, Ready, timeout) shouldBe Ready
            monitor.awaitOnNext(3, timeout) shouldBe true

            // emulate broken connection
            //listeners.foreach(_.disconnected(client))

            monitor.awaitOnNext(7, timeout) shouldBe true
            awaitState(vtep, Ready, timeout) shouldBe Ready

            val events = monitor.getOnNextEvents.toList
            events shouldBe
                List(Disconnected, Connected, Ready, Broken,
                     Connecting, Connected, Ready)

            //Mockito.verify(connectionService, Mockito.times(1))
            //    .registerConnectionListener(
            //        MockitoMatchers.any[OvsdbConnectionListener])
            //Mockito.verify(connectionService, Mockito.times(2))
            //    .connect(MockitoMatchers.any[InetAddress](),
            //             MockitoMatchers.anyInt())
            //Mockito.verifyNoMoreInteractions(connectionService)

            Mockito.verify(client, Mockito.times(2)).getDatabases
            Mockito.verify(client, Mockito.times(2))
                .getSchema(OvsdbTools.DB_HARDWARE_VTEP)
            Mockito.verifyNoMoreInteractions(client)
            subscription.unsubscribe()
        }

        scenario("bad address") {
            //Mockito
            //    .when(connectionService.connect(
            //        MockitoMatchers.any[InetAddress](),
            //        MockitoMatchers.anyInt()))
            //    .thenThrow(new RuntimeException("failed connection"))

            val vtep = new TestableConnection(endpoint, vtepThread, 0 seconds, 1)

            val monitor = new VtepObserver
            val subscription = vtep.observable.subscribe(monitor)

            vtep.connect()
            monitor.awaitOnNext(5, timeout) shouldBe true
            awaitState(vtep, Failed, timeout) shouldBe Failed

            val events = monitor.getOnNextEvents.toList
            events shouldBe
                List(Disconnected, Broken, Connecting, Broken, Failed)
            subscription.unsubscribe()
        }
    }
}
