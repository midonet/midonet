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
import java.util.UUID
import java.util.concurrent.{TimeUnit, ScheduledThreadPoolExecutor}

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import org.junit.runner.RunWith
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{Mockito, Matchers => MockitoMatchers}
import org.opendaylight.ovsdb.lib.{OvsdbConnectionListener, OvsdbClient, OvsdbConnection}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.data.vtep.VtepConnection.State._
import org.midonet.cluster.data.vtep.VtepException
import org.midonet.cluster.data.vtep.model.VtepEndPoint
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class OvsdbVtepConnectionTest extends FeatureSpec
                                      with Matchers
                                      with BeforeAndAfter {

    val Timeout = Duration(5, TimeUnit.SECONDS)

    val endpoint = VtepEndPoint("127.0.0.1", 6632)
    var vtepThread: ScheduledThreadPoolExecutor = _
    var connectionService: OvsdbConnection = _
    var listeners: Set[OvsdbConnectionListener] = _

    def awaitState(vtep: OvsdbVtepConnection, st: State, t: Duration): State =
        Await.result(vtep.futureState(Set(st)), t)

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

            awaitState(vtep, DISCONNECTED, Timeout) shouldBe DISCONNECTED
        }

        scenario("connect to vtep") {
            val client = Mockito.mock(classOf[OvsdbClient])
            Mockito
                .when(connectionService.connect(
                    MockitoMatchers.any[InetAddress](),
                    MockitoMatchers.anyInt()))
                .thenReturn(client)

            val user = UUID.randomUUID()
            val vtep = new OvsdbVtepConnection(endpoint, vtepThread,
                                               connectionService, 0, 1)

            val monitor = new AwaitableObserver[State](2)
            val subscription = vtep.stateObservable.subscribe(monitor)

            vtep.connect(user)
            awaitState(vtep, CONNECTED, Timeout) shouldBe CONNECTED
            monitor.await(Timeout)

            val events = monitor.getOnNextEvents.toList
            events shouldBe List(DISCONNECTED, CONNECTED)

            Mockito.verify(connectionService, Mockito.times(1))
                .registerConnectionListener(
                    MockitoMatchers.any[OvsdbConnectionListener])
            Mockito.verify(connectionService, Mockito.times(1))
                .connect(MockitoMatchers.any[InetAddress](),
                         MockitoMatchers.anyInt())
            Mockito.verifyNoMoreInteractions(connectionService)
        }

        scenario("disconnect from vtep") {
            val client = Mockito.mock(classOf[OvsdbClient])
            Mockito
                .when(connectionService.connect(
                MockitoMatchers.any[InetAddress](),
                MockitoMatchers.anyInt()))
                .thenReturn(client)

            val user = UUID.randomUUID()
            val vtep = new OvsdbVtepConnection(endpoint, vtepThread,
                                               connectionService, 0, 1)

            val monitor = new AwaitableObserver[State](2)
            val subscription = vtep.stateObservable.subscribe(monitor)

            vtep.connect(user)
            awaitState(vtep, CONNECTED, Timeout) shouldBe CONNECTED
            monitor.await(Timeout, 1)

            vtep.disconnect(user)
            awaitState(vtep, DISCONNECTING, Timeout) shouldBe DISCONNECTING
            monitor.await(Timeout, 1)

            // emulate async close event
            listeners.foreach(_.disconnected(client))

            awaitState(vtep, DISCONNECTED, Timeout) shouldBe DISCONNECTED
            monitor.await(Timeout, 0)

            val events = monitor.getOnNextEvents.toList
            events shouldBe
                List(DISCONNECTED, CONNECTED, DISCONNECTING, DISCONNECTED)

            Mockito.verify(connectionService, Mockito.times(1))
                .registerConnectionListener(
                    MockitoMatchers.any[OvsdbConnectionListener])
            Mockito.verify(connectionService, Mockito.times(1))
                .connect(MockitoMatchers.any[InetAddress](),
                         MockitoMatchers.anyInt())
            Mockito.verify(connectionService, Mockito.times(1))
                .disconnect(client)
            Mockito.verifyNoMoreInteractions(connectionService)
        }

        scenario("multi-user connection") {
            val client = Mockito.mock(classOf[OvsdbClient])
            Mockito
                .when(connectionService.connect(
                MockitoMatchers.any[InetAddress](),
                MockitoMatchers.anyInt()))
                .thenReturn(client)

            val user1 = UUID.randomUUID()
            val user2 = UUID.randomUUID()
            val vtep = new OvsdbVtepConnection(endpoint, vtepThread,
                                               connectionService, 0, 1)

            val monitor = new AwaitableObserver[State](2)
            val subscription = vtep.stateObservable.subscribe(monitor)

            vtep.connect(user1)
            vtep.connect(user2)
            awaitState(vtep, CONNECTED, Timeout) shouldBe CONNECTED
            monitor.await(Timeout, 1)

            vtep.disconnect(user1)
            Thread.sleep(500)
            vtep.getState shouldBe CONNECTED

            vtep.disconnect(user2)
            awaitState(vtep, DISCONNECTING, Timeout) shouldBe DISCONNECTING
            monitor.await(Timeout, 1)

            // emulate async close event
            listeners.foreach(_.disconnected(client))

            awaitState(vtep, DISCONNECTED, Timeout) shouldBe DISCONNECTED
            monitor.await(Timeout, 0)

            val events = monitor.getOnNextEvents.toList
            events shouldBe
                List(DISCONNECTED, CONNECTED, DISCONNECTING, DISCONNECTED)

            Mockito.verify(connectionService, Mockito.times(1))
                .registerConnectionListener(
                    MockitoMatchers.any[OvsdbConnectionListener])
            Mockito.verify(connectionService, Mockito.times(1))
                .connect(MockitoMatchers.any[InetAddress](),
                         MockitoMatchers.anyInt())
            Mockito.verify(connectionService, Mockito.times(1))
                .disconnect(client)
            Mockito.verifyNoMoreInteractions(connectionService)
        }

        scenario("automatic reconnection") {
            val client = Mockito.mock(classOf[OvsdbClient])
            Mockito
                .when(connectionService.connect(
                MockitoMatchers.any[InetAddress](),
                MockitoMatchers.anyInt()))
                .thenReturn(client)

            val user = UUID.randomUUID()
            val vtep = new OvsdbVtepConnection(endpoint, vtepThread,
                                               connectionService, 0, 1)

            val monitor = new AwaitableObserver[State](2)
            val subscription = vtep.stateObservable.subscribe(monitor)

            vtep.connect(user)
            awaitState(vtep, CONNECTED, Timeout) shouldBe CONNECTED
            monitor.await(Timeout, 3)

            // emulate broken connection
            listeners.foreach(_.disconnected(client))

            monitor.await(Timeout)
            awaitState(vtep, CONNECTED, Timeout) shouldBe CONNECTED

            val events = monitor.getOnNextEvents.toList
            events shouldBe
                List(DISCONNECTED, CONNECTED, BROKEN, CONNECTING, CONNECTED)

            Mockito.verify(connectionService, Mockito.times(1))
                .registerConnectionListener(
                    MockitoMatchers.any[OvsdbConnectionListener])
            Mockito.verify(connectionService, Mockito.times(2))
                .connect(MockitoMatchers.any[InetAddress](),
                         MockitoMatchers.anyInt())
            Mockito.verifyNoMoreInteractions(connectionService)
        }

        scenario("bad address") {
            val client = Mockito.mock(classOf[OvsdbClient])
            Mockito
                .when(connectionService.connect(
                    MockitoMatchers.any[InetAddress](),
                    MockitoMatchers.anyInt()))
                .thenThrow(new RuntimeException("failed connection"))

            val user = UUID.randomUUID()
            val vtep = new OvsdbVtepConnection(endpoint, vtepThread,
                                               connectionService, 0, 1)

            val monitor = new AwaitableObserver[State](4)
            val subscription = vtep.stateObservable.subscribe(monitor)

            vtep.connect(user)
            monitor.await(Timeout)
            awaitState(vtep, BROKEN, Timeout) shouldBe BROKEN

            val events = monitor.getOnNextEvents.toList
            events shouldBe
                List(DISCONNECTED, BROKEN, CONNECTING, BROKEN)
        }
    }
}
