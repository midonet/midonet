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

import java.net.InetSocketAddress
import java.util.UUID

import scala.concurrent.{Future, Promise}

import com.typesafe.config.ConfigFactory

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.GetChildrenBuilder
import org.apache.curator.framework.listen.Listenable
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.Watcher
import org.junit.runner.RunWith
import org.mockito.Matchers.any
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.StateProxyConfig
import org.midonet.cluster.data.storage.{StateStorage, StateTableStorage, Storage}
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.rpc.State.ProxyRequest.{Subscribe, Unsubscribe}
import org.midonet.cluster.rpc.State.ProxyResponse
import org.midonet.cluster.rpc.State.ProxyResponse.{Acknowledge, Notify}
import org.midonet.cluster.rpc.State.ProxyResponse.Error.Code
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.discovery.{FakeDiscovery, MidonetDiscovery}
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.cluster.services.state.server.{ClientHandler, ClientUnregisteredException}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.MAC
import org.midonet.util.eventloop.Reactor

@RunWith(classOf[JUnitRunner])
class StateTableManagerTest extends FeatureSpec with Matchers
                            with GivenWhenThen with BeforeAndAfter {

    private class TestBackend extends MidonetBackend {
        val connectionListener =
            Mockito.mock(classOf[Listenable[ConnectionStateListener]])
        val getChildren = Mockito.mock(classOf[GetChildrenBuilder])

        override def stateStore: StateStorage = ???
        override def store: Storage = ???
        override val curator: CuratorFramework = {
            val curator = Mockito.mock(classOf[CuratorFramework])
            Mockito.when(curator.getConnectionStateListenable)
                   .thenReturn(connectionListener)
            Mockito.when(curator.getChildren).thenReturn(getChildren)
            Mockito.when(getChildren.usingWatcher(any[Watcher]()))
                   .thenReturn(getChildren)
            Mockito.when(getChildren.inBackground(any(), any[Object]()))
                   .thenReturn(getChildren)
            curator
        }
        override def connectionState: Observable[ConnectionState] = ???
        override def failFastConnectionState: Observable[ConnectionState] = ???
        override val stateTableStore: StateTableStorage =
            Mockito.mock(classOf[StateTableStorage])
        override def failFastCurator: CuratorFramework = ???
        override def reactor: Reactor = ???
        override def doStop(): Unit = ???
        override def doStart(): Unit = ???
        override def stateTableClient: StateTableClient = ???
        override val discovery: MidonetDiscovery = new FakeDiscovery
    }

    private val config = new StateProxyConfig(ConfigFactory.parseString(
        s"""
           |cluster.state_proxy.cache_threads : 1
           |cluster.state_proxy.server.shutdown_timeout : 10ms
           |cluster.state_proxy.initial_subscriber_queue_size : 4
           |cluster.state_proxy.notify_batch_size : 16
         """.stripMargin))

    private def newBackend = new TestBackend

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

    feature("Manager handle clients") {
        scenario("Manager is closed") {
            Given("A state table manager")
            val manager = new StateTableManager(config, null)

            When("The manager is closed")
            manager.close()

            Then("Registering a new client should fail")
            val handler = Mockito.mock(classOf[ClientHandler])
            intercept[IllegalStateException] {
                manager.register(new InetSocketAddress("1.2.3.4", 19999), handler)
            }

            And("Unregistering a client should fail")
            intercept[IllegalStateException] {
                manager.unregister(new InetSocketAddress("1.2.3.4", 19999))
            }

            And("Subscribing a client to a table should fail")
            intercept[IllegalStateException] {
                manager.subscribe(new InetSocketAddress("1.2.3.4", 19999), 0L,
                                  null)
            }

            And("Unsubscribing a client from a table should fail")
            intercept[IllegalStateException] {
                manager.unsubscribe(new InetSocketAddress("1.2.3.4", 19999), 0L,
                                    null)
            }
        }

        scenario("Manager registers and unregisters client") {
            Given("A state table manager")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            When("A new client registers")
            val address = new InetSocketAddress("1.2.3.4", 20000)
            val handler = Mockito.mock(classOf[ClientHandler])
            manager.register(address, handler)

            Then("The client should be able to unregister")
            manager.unregister(address)

            And("The handler should be closed")
            Mockito.verify(handler).close()
        }

        scenario("Manager re-registers client for the same address") {
            Given("A state table manager")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            And("A registered client")
            val address = new InetSocketAddress("1.2.3.4", 20000)
            val handler1 = Mockito.mock(classOf[ClientHandler])
            manager.register(address, handler1)

            When("Registering a client for the same address")
            val handler2 = Mockito.mock(classOf[ClientHandler])
            manager.register(address, handler2)

            Then("The first hanlder should be closed")
            Mockito.verify(handler1).close()

            When("Unregistering the client")
            manager.unregister(address)

            Then("The second handler should be closed")
            Mockito.verify(handler2).close()
        }

        scenario("Unregistering non existing client") {
            Given("A state table manager")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            Then("Unregistering a non-existing client does nothing")
            manager.unregister(new InetSocketAddress("1.2.3.4", 20000))
        }

        scenario("Manager handles multiple clients") {
            Given("A state table manager")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            And("Two registered clients")
            val address1 = new InetSocketAddress("1.2.3.4", 20000)
            val handler1 = Mockito.mock(classOf[ClientHandler])
            manager.register(address1, handler1)

            val address2 = new InetSocketAddress("5.6.7.8", 20001)
            val handler2 = Mockito.mock(classOf[ClientHandler])
            manager.register(address2, handler2)

            Mockito.verifyZeroInteractions(handler1)
            Mockito.verifyZeroInteractions(handler2)

            When("Unregistering the first client")
            manager.unregister(address1)

            Then("The first handler should be closed")
            Mockito.verify(handler1).close()
            Mockito.verifyZeroInteractions(handler2)

            When("Unregistering the second client")
            manager.unregister(address2)

            Then("The second handler should be closed")
            Mockito.verify(handler2).close()
        }

        scenario("Manager closes client contexts on close") {
            Given("A state table manager")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            And("Two registered clients")
            val address1 = new InetSocketAddress("1.2.3.4", 20000)
            val handler1 = Mockito.mock(classOf[ClientHandler])
            manager.register(address1, handler1)

            val address2 = new InetSocketAddress("5.6.7.8", 20001)
            val handler2 = Mockito.mock(classOf[ClientHandler])
            manager.register(address2, handler2)

            When("Closing the manager")
            manager.close()

            Then("The manager should close the clients")
            Mockito.verify(handler1).close()
            Mockito.verify(handler2).close()
        }

        scenario("Manager handles context close timeouts") {
            Given("A state table manager")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            When("A new client registers")
            val address = new InetSocketAddress("1.2.3.4", 20000)
            val handler = Mockito.mock(classOf[ClientHandler])
            Mockito.when(handler.close()).thenReturn(Promise[AnyRef]().future)
            manager.register(address, handler)

            Then("Closing the manager completes")
            manager.close()
        }
    }

    feature("Manager handles subscriptions") {
        scenario("Manager validates subscription request") {
            Given("A state manager table")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            And("A registered client")
            val address = new InetSocketAddress("1.2.3.4", 20000)
            val handler = Mockito.mock(classOf[ClientHandler])
            manager.register(address, handler)

            And("A request builder")
            val builder = Subscribe.newBuilder()

            Then("A subscribe request without object class fails")
            var e = intercept[StateTableException] {
                manager.subscribe(address, 0L, builder.build())
            }
            e.code shouldBe Code.INVALID_ARGUMENT
            builder.setObjectClass(classOf[Network].getName)

            And("A subscribe request without object identifier fails")
            e = intercept[StateTableException] {
                manager.subscribe(address, 0L, builder.build())
            }
            e.code shouldBe Code.INVALID_ARGUMENT
            val objId = UUID.randomUUID()
            builder.setObjectId(objId.asProto)

            And("A subscribe request without key class fails")
            e = intercept[StateTableException] {
                manager.subscribe(address, 0L, builder.build())
            }
            e.code shouldBe Code.INVALID_ARGUMENT
            builder.setKeyClass(classOf[MAC].getName)

            And("A subscribe request without value class fails")
            e = intercept[StateTableException] {
                manager.subscribe(address, 0L, builder.build())
            }
            e.code shouldBe Code.INVALID_ARGUMENT
            builder.setValueClass(classOf[UUID].getName)

            And("A subscribe request without table name fails")
            e = intercept[StateTableException] {
                manager.subscribe(address, 0L, builder.build())
            }
            e.code shouldBe Code.INVALID_ARGUMENT
        }

        scenario("Manager validates unsubscribe request") {
            Given("A state manager table")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            And("A registered client")
            val address = new InetSocketAddress("1.2.3.4", 20000)
            val handler = Mockito.mock(classOf[ClientHandler])
            manager.register(address, handler)

            And("A request builder")
            val builder = Unsubscribe.newBuilder()

            Then("A subscribe request without object class fails")
            val e = intercept[StateTableException] {
                manager.unsubscribe(address, 0L, builder.build())
            }
            e.code shouldBe Code.INVALID_ARGUMENT
        }

        scenario("Subscribing for unregistered client fails") {
            Given("A state manager table")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            And("A client address")
            val address = new InetSocketAddress("1.2.3.4", 20000)

            And("A subscribe request")
            val objId = UUID.randomUUID()
            val request = Subscribe.newBuilder()
                .setObjectClass(classOf[Network].getName)
                .setObjectId(objId.asProto)
                .setKeyClass(classOf[MAC].getName)
                .setValueClass(classOf[UUID].getName)
                .setTableName("mac_port")
                .build()

            Then("Subscribing for a non-existing client should fail")
            val e = intercept[ClientUnregisteredException] {
                manager.subscribe(address, 0L, request)
            }
            e.clientAddress shouldBe address
        }

        scenario("Manager handles non-existing classes") {
            Given("A state manager table")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            And("A registered client")
            val address = new InetSocketAddress("1.2.3.4", 20000)
            val handler = Mockito.mock(classOf[ClientHandler])
            manager.register(address, handler)

            And("A subscribe request")
            val objId = UUID.randomUUID()
            val request = Subscribe.newBuilder()
                .setObjectClass("org.midonet.SomeUnknownClass")
                .setObjectId(objId.asProto)
                .setKeyClass("org.midonet.SomeUnknownClass")
                .setValueClass("org.midonet.SomeUnknownClass")
                .setTableName("mac_port")
                .build()

            Then("Subscribing for a non-existing client should fail")
            val e = intercept[StateTableException] {
                manager.subscribe(address, 0L, request)
            }
            e.code shouldBe Code.INVALID_ARGUMENT
        }

        scenario("Client subscribes without last version") {
            Given("A state manager table")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            And("A registered client")
            val address = new InetSocketAddress("1.2.3.4", 20000)
            val handler = Mockito.mock(classOf[ClientHandler])
            Mockito.when(handler.send(any())).thenReturn(Future.successful(null))
            manager.register(address, handler)

            And("A subscribe request")
            val objId = UUID.randomUUID()
            val subscribe = Subscribe.newBuilder()
                .setObjectClass(classOf[Network].getName)
                .setObjectId(objId.asProto)
                .setKeyClass(classOf[MAC].getName)
                .setValueClass(classOf[UUID].getName)
                .setTableName("mac_port")
                .build()

            When("The client subscribes to the state table")
            manager.subscribe(address, 0L, subscribe)

            Then("The manager acknowledges the subscription")
            Mockito.verify(handler).send(acknowledge(0L, 1L, None))

            And("The cache adds a connection listener")
            Mockito.verify(backend.connectionListener).addListener(any())

            And("The cache requests the table entries")
            Mockito.verify(backend.getChildren).usingWatcher(any[Watcher]())
            Mockito.verify(backend.getChildren).inBackground(any(), any[Object]())
            Mockito.verify(backend.getChildren).forPath(any())

            When("The client unsubscribes from the state table")
            val unsubscribe = Unsubscribe.newBuilder()
                .setSubscriptionId(1L).build()
            manager.unsubscribe(address, 1L, unsubscribe)

            Then("The manager acknowledges the unsubscribe")
            Mockito.verify(handler).send(acknowledge(1L, 1L, None))

            And("The cache closes")
            Mockito.verify(backend.connectionListener).removeListener(any())
            Mockito.verify(backend.curator).clearWatcherReferences(any())
        }

        scenario("Manager handles multiple subscriptions to the same table") {
            Given("A state manager table")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            And("A registered client")
            val address1 = new InetSocketAddress("1.2.3.4", 20000)
            val handler1 = Mockito.mock(classOf[ClientHandler])
            Mockito.when(handler1.send(any())).thenReturn(Future.successful(null))
            manager.register(address1, handler1)

            And("A subscribe request")
            val objId = UUID.randomUUID()
            val subscribe = Subscribe.newBuilder()
                .setObjectClass(classOf[Network].getName)
                .setObjectId(objId.asProto)
                .setKeyClass(classOf[MAC].getName)
                .setValueClass(classOf[UUID].getName)
                .setTableName("mac_port")
                .build()

            When("The client subscribes to the state table")
            manager.subscribe(address1, 0L, subscribe)

            Then("The manager acknowledges the subscription")
            Mockito.verify(handler1).send(acknowledge(0L, 1L, None))

            When("A second client registers")
            val address2 = new InetSocketAddress("5.6.7.8", 20001)
            val handler2 = Mockito.mock(classOf[ClientHandler])
            Mockito.when(handler2.send(any())).thenReturn(Future.successful(null))
            manager.register(address2, handler2)

            And("Subscribes to the same table")
            manager.subscribe(address2, 1L, subscribe)

            Then("The manager acknowledges the subscription")
            Mockito.verify(handler2).send(acknowledge(1L, 2L, None))

            And("The cache should be started only once")
            Mockito.verify(backend.getChildren, times(1))
                   .usingWatcher(any[Watcher]())
            Mockito.verify(backend.getChildren, times(1))
                   .inBackground(any(), any[Object]())
            Mockito.verify(backend.getChildren, times(1)).forPath(any())

            When("The first client unsubscribes")
            var unsubscribe = Unsubscribe.newBuilder()
                .setSubscriptionId(1L).build()
            manager.unsubscribe(address1, 1L, unsubscribe)

            Then("The manager acknowledges the unsubscribe")
            Mockito.verify(handler1).send(acknowledge(1L, 1L, None))

            And("The cache should not close")
            Mockito.verify(backend.connectionListener, times(0))
                   .removeListener(any())
            Mockito.verify(backend.curator, times(0))
                   .clearWatcherReferences(any())

            When("The second client unsubscribes")
            unsubscribe = Unsubscribe.newBuilder()
                .setSubscriptionId(2L).build()
            manager.unsubscribe(address2, 2L, unsubscribe)

            Then("The manager acknowledges the unsubscribe")
            Mockito.verify(handler2).send(acknowledge(2L, 2L, None))

            And("The cache closes")
            Mockito.verify(backend.connectionListener).removeListener(any())
            Mockito.verify(backend.curator).clearWatcherReferences(any())
        }

        scenario("Manager receives unsubscribe for unknown client") {
            Given("A state manager table")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            Then("An unsubscribe from an unknown client should fail")
            val address = new InetSocketAddress("1.2.3.4", 20000)
            val unsubscribe = Unsubscribe.newBuilder()
                .setSubscriptionId(1L).build()
            val e = intercept[ClientUnregisteredException] {
                manager.unsubscribe(address, 0L, unsubscribe)
            }
            e.clientAddress shouldBe address
        }

        scenario("Manager receives unsubscribe for unknown subscription") {
            Given("A state manager table")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            And("A registered client")
            val address = new InetSocketAddress("1.2.3.4", 20000)
            val handler = Mockito.mock(classOf[ClientHandler])
            manager.register(address, handler)

            Then("An unsubscribe request for an unknown subscription should fail")
            val unsubscribe = Unsubscribe.newBuilder()
                .setSubscriptionId(1L).build()
            val e = intercept[StateTableException] {
                manager.unsubscribe(address, 0L, unsubscribe)
            }
            e.code shouldBe Code.NO_SUBSCRIPTION
        }

        scenario("Manager closes caches for existing subscriptions") {
            Given("A state manager table")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            And("A registered client")
            val address = new InetSocketAddress("1.2.3.4", 20000)
            val handler = Mockito.mock(classOf[ClientHandler])
            Mockito.when(handler.send(any())).thenReturn(Future.successful(null))
            manager.register(address, handler)

            And("A subscribe request")
            val objId = UUID.randomUUID()
            val subscribe = Subscribe.newBuilder()
                .setObjectClass(classOf[Network].getName)
                .setObjectId(objId.asProto)
                .setKeyClass(classOf[MAC].getName)
                .setValueClass(classOf[UUID].getName)
                .setTableName("mac_port")
                .build()

            When("The client subscribes to the state table")
            manager.subscribe(address, 0L, subscribe)

            Then("The manager acknowledges the subscription")
            Mockito.verify(handler).send(acknowledge(0L, 1L, None))

            When("The manager closes")
            manager.close()

            Then("The manager should notify the client of completed")
            Mockito.verify(handler).send(notifyCompleted(0L, 1L))

            And("The client handler should be closed")
            Mockito.verify(handler).close()

            And("The cache closes")
            Mockito.verify(backend.connectionListener).removeListener(any())
            Mockito.verify(backend.curator).clearWatcherReferences(any())
        }

        scenario("Manager unsubscribes unregistered clients") {
            Given("A state manager table")
            val backend = newBackend
            val manager = new StateTableManager(config, backend)

            And("A registered client")
            val address = new InetSocketAddress("1.2.3.4", 20000)
            val handler = Mockito.mock(classOf[ClientHandler])
            Mockito.when(handler.send(any())).thenReturn(Future.successful(null))
            manager.register(address, handler)

            And("A subscribe request")
            val objId = UUID.randomUUID()
            val subscribe = Subscribe.newBuilder()
                .setObjectClass(classOf[Network].getName)
                .setObjectId(objId.asProto)
                .setKeyClass(classOf[MAC].getName)
                .setValueClass(classOf[UUID].getName)
                .setTableName("mac_port")
                .build()

            When("The client subscribes to the state table")
            manager.subscribe(address, 0L, subscribe)

            Then("The manager acknowledges the subscription")
            Mockito.verify(handler).send(acknowledge(0L, 1L, None))

            When("The client closes the connection")
            manager.unregister(address)

            Then("The cache closes")
            Mockito.verify(backend.connectionListener).removeListener(any())
            Mockito.verify(backend.curator).clearWatcherReferences(any())

            And("The manager does not send any further messages")
            Mockito.verify(handler, times(1)).send(any())
        }
    }

}
