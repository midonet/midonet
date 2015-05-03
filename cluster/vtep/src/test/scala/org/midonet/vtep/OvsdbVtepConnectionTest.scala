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
import java.util.UUID
import java.util.concurrent.{Executor, ScheduledThreadPoolExecutor, TimeUnit}

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

import com.google.common.util.concurrent.ListenableFuture
import org.junit.runner.RunWith
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{Matchers => MockitoMatchers, Mockito}
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.opendaylight.ovsdb.lib.{OvsdbClient, OvsdbConnection, OvsdbConnectionListener}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.data.vtep.VtepConnection.State._
import org.midonet.cluster.data.vtep.model.VtepEndPoint
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
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
    var connectionService: OvsdbConnection = _
    var listeners: Set[OvsdbConnectionListener] = _

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

    before {
        vtepThread = new ScheduledThreadPoolExecutor(1)
        connectionService = Mockito.mock(classOf[OvsdbConnection])
        listeners = Set()
        Mockito
            .when(connectionService.registerConnectionListener(
                  MockitoMatchers.any[OvsdbConnectionListener]))
            .then(new Answer[Unit] {
            override def answer(invocation: InvocationOnMock): Unit = {
                val args = invocation.getArguments
                val cb = args(0).asInstanceOf[OvsdbConnectionListener]
                listeners = listeners + cb
            }
        })
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
            val vtep = new OvsdbVtepConnection(endpoint, vtepThread,
                                               connectionService, 0, 1)
            vtep.getState shouldBe DISCONNECTED
            listeners.size shouldBe 1

            Mockito.verify(connectionService, Mockito.times(1))
                .registerConnectionListener(
                    MockitoMatchers.any[OvsdbConnectionListener])
            Mockito.verifyNoMoreInteractions(connectionService)

            awaitState(vtep, DISCONNECTED, timeout) shouldBe DISCONNECTED
        }

        scenario("connect to vtep") {
            val client = newMockClient
            Mockito
                .when(connectionService.connect(
                    MockitoMatchers.any[InetAddress](),
                    MockitoMatchers.anyInt()))
                .thenReturn(client)

            val user = UUID.randomUUID()
            val vtep = new OvsdbVtepConnection(endpoint, vtepThread,
                                               connectionService, 0, 1)

            val monitor = new VtepObserver
            val subscription = vtep.observable.subscribe(monitor)

            vtep.connect(user)
            awaitState(vtep, READY, timeout) shouldBe READY
            monitor.awaitOnNext(3, timeout) shouldBe true

            val events = monitor.getOnNextEvents.toList
            events shouldBe List(DISCONNECTED, CONNECTED, READY)

            Mockito.verify(connectionService, Mockito.times(1))
                .registerConnectionListener(
                    MockitoMatchers.any[OvsdbConnectionListener])
            Mockito.verify(connectionService, Mockito.times(1))
                .connect(MockitoMatchers.any[InetAddress](),
                         MockitoMatchers.anyInt())
            Mockito.verifyNoMoreInteractions(connectionService)

            Mockito.verify(client, Mockito.times(1)).getDatabases
            Mockito.verify(client, Mockito.times(1))
                .getSchema(OvsdbTools.DB_HARDWARE_VTEP)
            Mockito.verifyNoMoreInteractions(client)
            subscription.unsubscribe()
        }

        scenario("disconnect from vtep") {
            val client = newMockClient
            Mockito
                .when(connectionService.connect(
                MockitoMatchers.any[InetAddress](),
                MockitoMatchers.anyInt()))
                .thenReturn(client)

            val user = UUID.randomUUID()
            val vtep = new OvsdbVtepConnection(endpoint, vtepThread,
                                               connectionService, 0, 1)

            val monitor = new VtepObserver
            val subscription = vtep.observable.subscribe(monitor)

            vtep.connect(user)
            awaitState(vtep, READY, timeout) shouldBe READY
            monitor.awaitOnNext(3, timeout) shouldBe true

            vtep.disconnect(user)
            awaitState(vtep, DISCONNECTING, timeout) shouldBe DISCONNECTING
            monitor.awaitOnNext(4, timeout) shouldBe true

            // emulate async close event
            listeners.foreach(_.disconnected(client))

            awaitState(vtep, DISCONNECTED, timeout) shouldBe DISCONNECTED
            monitor.awaitOnNext(5, timeout) shouldBe true

            val events = monitor.getOnNextEvents.toList
            events shouldBe
                List(DISCONNECTED, CONNECTED, READY, DISCONNECTING, DISCONNECTED)

            Mockito.verify(connectionService, Mockito.times(1))
                .registerConnectionListener(
                    MockitoMatchers.any[OvsdbConnectionListener])
            Mockito.verify(connectionService, Mockito.times(1))
                .connect(MockitoMatchers.any[InetAddress](),
                         MockitoMatchers.anyInt())
            Mockito.verify(connectionService, Mockito.times(1))
                .disconnect(client)
            Mockito.verifyNoMoreInteractions(connectionService)

            Mockito.verify(client, Mockito.times(1)).getDatabases
            Mockito.verify(client, Mockito.times(1))
                .getSchema(OvsdbTools.DB_HARDWARE_VTEP)
            Mockito.verifyNoMoreInteractions(client)
            subscription.unsubscribe()
        }

        scenario("multi-user connection") {
            val client = newMockClient
            Mockito
                .when(connectionService.connect(
                MockitoMatchers.any[InetAddress](),
                MockitoMatchers.anyInt()))
                .thenReturn(client)

            val user1 = UUID.randomUUID()
            val user2 = UUID.randomUUID()
            val vtep = new OvsdbVtepConnection(endpoint, vtepThread,
                                               connectionService, 0, 1)

            val monitor = new VtepObserver
            val subscription = vtep.observable.subscribe(monitor)

            vtep.connect(user1)
            vtep.connect(user2)
            awaitState(vtep, READY, timeout) shouldBe READY
            monitor.awaitOnNext(3, timeout) shouldBe true

            vtep.disconnect(user1)
            Thread.sleep(500)
            vtep.getState shouldBe READY

            vtep.disconnect(user2)
            awaitState(vtep, DISCONNECTING, timeout) shouldBe DISCONNECTING
            monitor.awaitOnNext(4, timeout) shouldBe true

            // emulate async close event
            listeners.foreach(_.disconnected(client))

            awaitState(vtep, DISCONNECTED, timeout) shouldBe DISCONNECTED
            monitor.awaitOnNext(5, timeout) shouldBe true

            val events = monitor.getOnNextEvents.toList
            events shouldBe
                List(DISCONNECTED, CONNECTED, READY, DISCONNECTING, DISCONNECTED)

            Mockito.verify(connectionService, Mockito.times(1))
                .registerConnectionListener(
                    MockitoMatchers.any[OvsdbConnectionListener])
            Mockito.verify(connectionService, Mockito.times(1))
                .connect(MockitoMatchers.any[InetAddress](),
                         MockitoMatchers.anyInt())
            Mockito.verify(connectionService, Mockito.times(1))
                .disconnect(client)
            Mockito.verifyNoMoreInteractions(connectionService)

            Mockito.verify(client, Mockito.times(1)).getDatabases
            Mockito.verify(client, Mockito.times(1))
                .getSchema(OvsdbTools.DB_HARDWARE_VTEP)
            Mockito.verifyNoMoreInteractions(client)
            subscription.unsubscribe()
        }

        scenario("automatic reconnection") {
            val client = newMockClient
            Mockito
                .when(connectionService.connect(
                MockitoMatchers.any[InetAddress](),
                MockitoMatchers.anyInt()))
                .thenReturn(client)

            val user = UUID.randomUUID()
            val vtep = new OvsdbVtepConnection(endpoint, vtepThread,
                                               connectionService, 0, 1)

            val monitor = new VtepObserver
            val subscription = vtep.observable.subscribe(monitor)

            vtep.connect(user)
            awaitState(vtep, READY, timeout) shouldBe READY
            monitor.awaitOnNext(3, timeout) shouldBe true

            // emulate broken connection
            listeners.foreach(_.disconnected(client))

            monitor.awaitOnNext(7, timeout) shouldBe true
            awaitState(vtep, READY, timeout) shouldBe READY

            val events = monitor.getOnNextEvents.toList
            events shouldBe
                List(DISCONNECTED, CONNECTED, READY, BROKEN,
                     CONNECTING, CONNECTED, READY)

            Mockito.verify(connectionService, Mockito.times(1))
                .registerConnectionListener(
                    MockitoMatchers.any[OvsdbConnectionListener])
            Mockito.verify(connectionService, Mockito.times(2))
                .connect(MockitoMatchers.any[InetAddress](),
                         MockitoMatchers.anyInt())
            Mockito.verifyNoMoreInteractions(connectionService)

            Mockito.verify(client, Mockito.times(2)).getDatabases
            Mockito.verify(client, Mockito.times(2))
                .getSchema(OvsdbTools.DB_HARDWARE_VTEP)
            Mockito.verifyNoMoreInteractions(client)
            subscription.unsubscribe()
        }

        scenario("bad address") {
            Mockito
                .when(connectionService.connect(
                    MockitoMatchers.any[InetAddress](),
                    MockitoMatchers.anyInt()))
                .thenThrow(new RuntimeException("failed connection"))

            val user = UUID.randomUUID()
            val vtep = new OvsdbVtepConnection(endpoint, vtepThread,
                                               connectionService, 0, 1)

            val monitor = new VtepObserver
            val subscription = vtep.observable.subscribe(monitor)

            vtep.connect(user)
            monitor.awaitOnNext(5, timeout) shouldBe true
            awaitState(vtep, DISPOSED, timeout) shouldBe DISPOSED

            val events = monitor.getOnNextEvents.toList
            events shouldBe
                List(DISCONNECTED, BROKEN, CONNECTING, BROKEN, DISPOSED)
            subscription.unsubscribe()
        }
    }
}
