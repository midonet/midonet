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

import java.net._
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NonFatal

import com.typesafe.config.ConfigFactory

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder, ProtobufVarint32FrameDecoder, ProtobufVarint32LengthFieldPrepender}

import org.junit.runner.RunWith
import org.mockito.Matchers.any
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import rx.subjects.PublishSubject

import org.midonet.cluster.StateProxyConfig
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.rpc.State.ProxyRequest.{Ping, Subscribe, Unsubscribe}
import org.midonet.cluster.rpc.State.ProxyResponse.Error.Code
import org.midonet.cluster.rpc.State.ProxyResponse.{Acknowledge, Notify}
import org.midonet.cluster.rpc.State.{KeyValue, ProxyRequest, ProxyResponse}
import org.midonet.cluster.services.discovery.FakeDiscovery
import org.midonet.cluster.services.state.{StateProxyService, StateTableException, StateTableManager}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.MAC
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent._
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class StateProxyServerTest extends FeatureSpec with Matchers
                           with GivenWhenThen with MidonetEventually {

    private class TestClient(address: String, port: Int)
        extends ChannelInboundHandlerAdapter {

        private val subject = PublishSubject.create[AnyRef]
        private val eventLoop = new NioEventLoopGroup()
        private val bootstrap = new Bootstrap
        bootstrap.group(eventLoop)
        bootstrap.channel(classOf[NioSocketChannel])
        bootstrap.option(ChannelOption.TCP_NODELAY, Boolean.box(true))
        bootstrap.handler(new ChannelInitializer[SocketChannel] {
            @throws[Exception]
            override def initChannel(channel: SocketChannel): Unit = {
                channel.pipeline().addLast(
                    new ProtobufVarint32FrameDecoder,
                    new ProtobufDecoder(ProxyResponse.getDefaultInstance),
                    new ProtobufVarint32LengthFieldPrepender,
                    new ProtobufEncoder,
                    TestClient.this)
            }
        })

        val observer = new TestAwaitableObserver[AnyRef]
        subject subscribe observer
        val channel = bootstrap.connect("127.0.0.1", port).sync().channel()

        def close(): Unit = {
            channel.close().awaitUninterruptibly()
            eventLoop.shutdownGracefully().awaitUninterruptibly()
        }

        @throws[Exception]
        override def channelRead(context: ChannelHandlerContext,
                                 message: AnyRef): Unit = {
            subject onNext message
        }

        override def channelInactive(context: ChannelHandlerContext): Unit = {
            subject.onCompleted()
        }

        override def exceptionCaught(context: ChannelHandlerContext,
                                     cause: Throwable): Unit = {
            subject onError cause
        }
    }

    private class ClientContext(val handler: ClientHandler) {
        val subscriptions = new ConcurrentHashMap[Long, Subscribe]()
    }

    private class MockManager(registerError: Option[Throwable] = None,
                              unregisterError: Option[Throwable] = None,
                              subscribeError: Option[Throwable] = None,
                              unsubscribeError: Option[Throwable] = None) {
        val underlying = Mockito.mock(classOf[StateTableManager])
        val subscriptionId = new AtomicLong()
        val clients = new ConcurrentHashMap[SocketAddress, ClientContext]()
        Mockito.when(underlying.register(any(), any()))
               .thenAnswer(new Answer[Unit] {
            override def answer(invocation: InvocationOnMock): Unit = {
                if (registerError.isDefined) throw registerError.get
                val client = invocation.getArguments.apply(0).asInstanceOf[SocketAddress]
                val handler = invocation.getArguments.apply(1).asInstanceOf[ClientHandler]
                val context = new ClientContext(handler)
                clients.put(client, context)
            }
        })

        Mockito.when(underlying.subscribe(any(), any(), any()))
               .thenAnswer(new Answer[Unit] {
            override def answer(invocation: InvocationOnMock): Unit = {
                if (subscribeError.isDefined) throw subscribeError.get
                val client = invocation.getArguments.apply(0).asInstanceOf[SocketAddress]
                val requestId = invocation.getArguments.apply(1).asInstanceOf[Long]
                val subscribe = invocation.getArguments.apply(2).asInstanceOf[Subscribe]
                val context = clients.get(client)
                val id = subscriptionId.incrementAndGet()
                context.subscriptions.put(id, subscribe)
                context.handler.send(acknowledge(requestId, id, None))
            }
        })

        Mockito.when(underlying.unsubscribe(any(), any(), any()))
               .thenAnswer(new Answer[Unit] {
            override def answer(invocation: InvocationOnMock): Unit = {
                if (unsubscribeError.isDefined) throw unsubscribeError.get
                val client = invocation.getArguments.apply(0).asInstanceOf[SocketAddress]
                val requestId = invocation.getArguments.apply(1).asInstanceOf[Long]
                val unsubscribe = invocation.getArguments.apply(2).asInstanceOf[Unsubscribe]
                val context = clients.get(client)
                val subscribe = context.subscriptions.remove(unsubscribe.getSubscriptionId)
                if (subscribe ne null) {
                    context.handler.send(acknowledge(
                        requestId, unsubscribe.getSubscriptionId, None))
                } else {
                    context.handler.send(error(requestId, Code.NO_SUBSCRIPTION,
                                               ""))
                }
            }
        })

        Mockito.when(underlying.unregister(any())).thenAnswer(new Answer[Unit] {
            override def answer(invocation: InvocationOnMock): Unit = {
                if (unregisterError.isDefined) throw unregisterError.get
                val client = invocation.getArguments.apply(0).asInstanceOf[SocketAddress]
                clients.remove(client)
            }
        })

        Mockito.when(underlying.close()).thenAnswer(new Answer[Unit] {
            override def answer(invocation: InvocationOnMock): Unit = {
                val contextIterator = clients.values().iterator()
                while (contextIterator.hasNext) {
                    val context = contextIterator.next()
                    val subIterator = context.subscriptions.keySet().iterator()
                    while (subIterator.hasNext) {
                        val subId = subIterator.next()
                        Await.ready(
                            context.handler.send(notifyCompleted(-1L, subId)),
                            timeout)
                    }
                    context.handler.close()
                }
            }
        })
    }

    private val random = new Random()
    private val timeout = 30 seconds
    private val discovery = new FakeDiscovery

    private def localPort: Int = {
        var port = -1
        do {
            try {
                val socket = new ServerSocket(0)
                try {
                    socket.setReuseAddress(true)
                    port = socket.getLocalPort
                } finally {
                    socket.close()
                }
            } catch {
                case NonFatal(e) => port = -1
            }
        } while (port < 0)
        port
    }

    private def newConfig(supervisorThreads: Int = 1,
                          workerThreads: Int = 1,
                          maxPendingConnections: Int = 10,
                          bindRetryInterval: Int = 1): StateProxyConfig = {
        //val address = localAddress
        val port = localPort
        new StateProxyConfig(ConfigFactory.parseString(
            s"""
               |cluster.state_proxy.server.address : "127.0.0.1"
               |cluster.state_proxy.server.port : $port
               |cluster.state_proxy.server.interface : ""
               |cluster.state_proxy.server.supervisor_threads : $supervisorThreads
               |cluster.state_proxy.server.worker_threads : $workerThreads
               |cluster.state_proxy.server.max_pending_connections : $maxPendingConnections
               |cluster.state_proxy.server.bind_retry_interval : ${bindRetryInterval}s
               |cluster.state_proxy.server.channel_timeout : 15s
               |cluster.state_proxy.server.shutdown_quiet_period : 0s
               |cluster.state_proxy.server.shutdown_timeout : 15s
             """.stripMargin))
    }

    private def newServer(config: StateProxyConfig,
                          manager: StateTableManager =
                              Mockito.mock(classOf[StateTableManager]))
    : StateProxyServer = {
        new StateProxyServer(config, manager, discovery)
    }

    private def newClient(config: StateProxyConfig): TestClient = {
        new TestClient(config.serverAddress, config.serverPort)
    }

    private def subscribe(requestId: Long, objectClass: Class[_], objectId: UUID,
                          keyClass: Class[_], valueClass: Class[_],
                          tableName: String, lastVersion: Option[Long])
    : ProxyRequest = {
        val subscribe = Subscribe.newBuilder()
            .setObjectClass(objectClass.getName)
            .setObjectId(objectId.asProto)
            .setKeyClass(keyClass.getName)
            .setValueClass(valueClass.getName)
            .setTableName(tableName)
        if (lastVersion.isDefined)
            subscribe.setLastVersion(lastVersion.get)
        ProxyRequest.newBuilder()
            .setRequestId(requestId)
            .setSubscribe(subscribe)
            .build()
    }

    private def unsubscribe(requestId: Long, subscriptionId: Long): ProxyRequest = {
        val unsubscribe = Unsubscribe.newBuilder()
            .setSubscriptionId(subscriptionId)
        ProxyRequest.newBuilder()
            .setRequestId(requestId)
            .setUnsubscribe(unsubscribe)
            .build()
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

    private def error(requestId: Long, code: Code,
                      description: String): ProxyResponse = {
        val error = ProxyResponse.Error.newBuilder()
            .setCode(code)
            .setDescription(description)
        ProxyResponse.newBuilder()
            .setRequestId(requestId)
            .setError(error)
            .build()
    }

    private def ping(requestId: Long): ProxyRequest = {
        val ping = Ping.newBuilder().build()
        ProxyRequest.newBuilder()
            .setRequestId(requestId)
            .setPing(ping)
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

    feature("Test server connections") {
        scenario("Server initializes and closes") {
            Given("A state proxy server")
            val config = newConfig()
            val server = newServer(config)

            When("The server binds to the local port")
            val channel = server.serverChannel.await(timeout)

            Then("The channel should be open")
            channel.isOpen shouldBe true

            When("The server closes")
            server.close()

            Then("The channel should be closed")
            channel.isOpen shouldBe false
        }

        scenario("Server retries to bind if port in use") {
            Given("A state proxy server")
            val config = newConfig()
            val server1 = newServer(config)

            Then("The server should be bound to the local port")
            server1.serverChannel.await(timeout).isOpen shouldBe true

            When("A second server starts at the same port")
            val server2 = newServer(config)
            Thread sleep 200

            And("Closing the first server")
            server1.close()

            Then("The channel for the second server should eventually open")
            val channel = server2.serverChannel.await(timeout)
            channel.isOpen shouldBe true

            When("The second server closes")
            server2.close()

            Then("The channel should be closed")
            channel.isOpen shouldBe false
        }

        scenario("Server handles unset configuration") {
            Given("A state proxy server")
            val config = newConfig(supervisorThreads = 0,
                                   workerThreads = 0,
                                   maxPendingConnections = 0,
                                   bindRetryInterval = 0)
            val server = newServer(config)

            When("The server binds to the local port")
            val channel = server.serverChannel.await(timeout)

            Then("The channel should be open")
            channel.isOpen shouldBe true

            When("The server closes")
            server.close()

            Then("The channel should be closed")
            channel.isOpen shouldBe false
        }

        scenario("Server fails if port is in use") {
            Given("A state proxy server without bind retry")
            val config = newConfig(bindRetryInterval = 0)
            val server1 = newServer(config)

            Then("The server should be bound to the local port")
            server1.serverChannel.await(timeout).isOpen shouldBe true

            When("A second server starts at the same port")
            val server2 = newServer(config)

            Then("The second server should fail immediately")
            intercept[BindException] {
                server2.serverChannel.await(timeout)
            }

            And("Both servers can be closed")
            server1.close()
            server2.close()
        }

        scenario("Service registers in service discovery") {
            Given("A state proxy server")
            val config = newConfig()
            val server = newServer(config)
            val address = config.serverAddress
            val port = config.serverPort

            When("The server binds to the local port")
            val channel = server.serverChannel.await(timeout)

            Then("The service is registered in service discovery")
            discovery.registeredServices should contain only
                StateProxyService.Name -> new URI(null, null, address, port,
                                                  null, null, null)

            When("The server closes")
            server.close()

            Then("The service is not registered in service discovery")
            discovery.registeredServices shouldBe empty
        }
    }

    feature("Test client-server connectivity") {
        scenario("Server handles a client") {
            Given("A state proxy server")
            val config = newConfig()
            val server = newServer(config)

            When("The server starts")
            server.serverChannel.await(timeout)

            And("A client connects to the server")
            val client = newClient(config)

            Then("The client should be connected")
            client.channel.isOpen shouldBe true

            When("The client closes the connection")
            client.close()

            Then("The client should be disconnected")
            client.channel.isOpen shouldBe false

            And("The client channel should be inactive")
            client.observer.awaitCompletion(timeout)

            server.close()
        }

        scenario("Server handles multiple clients") {
            Given("A state proxy server")
            val config = newConfig()
            val server = newServer(config)

            When("The server starts")
            server.serverChannel.await(timeout)

            And("A set of clients")
            val clients = for (index <- 0 until 3) yield {
                newClient(config)
            }

            Then("The clients should be connected")
            for (client <- clients)
                client.channel.isOpen shouldBe true

            When("The clients close the connection")
            for (client <- clients)
                client.close()

            Then("The clients should be disconnected")
            for (client <- clients) {
                client.channel.isOpen shouldBe false
                client.observer.awaitCompletion(timeout)
            }

            server.close()
        }

        scenario("Server shutdown") {
            Given("A state proxy server")
            val config = newConfig()
            val server = newServer(config)

            When("The server starts")
            server.serverChannel.await(timeout)

            And("A client connects to the server")
            val client = newClient(config)

            Then("The client should be connected")
            client.channel.isOpen shouldBe true

            When("The server closes all connections")
            server.close()

            Then("The client should disconnect")
            client.observer.awaitCompletion(timeout)
            client.channel.isOpen shouldBe false
        }
    }

    feature("Test server handles protocol message") {
        scenario("Server handles client request") {
            Given("A state proxy server")
            val config = newConfig()
            val server = newServer(config)

            When("The server starts")
            server.serverChannel.await(timeout)

            And("A client connects to the server")
            val client = newClient(config)

            And("A client sending a dummy request")
            val requestId = random.nextLong()
            val request = ProxyRequest.newBuilder().setRequestId(requestId).build()
            client.channel.writeAndFlush(request)

            Then("The client should receive a response")
            client.observer.awaitOnNext(1, timeout)
            val response = client.observer.getOnNextEvents.get(0).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe requestId
            response.hasError shouldBe true
            response.getError.getCode shouldBe ProxyResponse.Error.Code.UNKNOWN_MESSAGE

            client.close()
            server.close()
        }

        scenario("Server handles unknown request") {
            Given("A state proxy server")
            val config = newConfig()
            val server = newServer(config)

            When("The server starts")
            server.serverChannel.await(timeout)

            And("A client connects to the server")
            val client = newClient(config)

            And("A client sending a dummy request")
            val requestId = random.nextLong()
            val request = KeyValue.newBuilder().build()
            client.channel.writeAndFlush(request)

            Then("The client should receive a response")
            client.observer.awaitOnNext(1, timeout)
            val response = client.observer.getOnNextEvents.get(0).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe -1L
            response.hasError shouldBe true
            response.getError.getCode shouldBe ProxyResponse.Error.Code.UNKNOWN_PROTOCOL

            client.close()
            server.close()
        }

        scenario("Server handles unknown data") {
            Given("A state proxy server")
            val config = newConfig()
            val server = newServer(config)

            When("The server starts")
            server.serverChannel.await(timeout)

            And("A client connects to the server")
            val client = newClient(config)

            And("A client sending a non-protocol request")
            val request = Unpooled.wrappedBuffer(new Array[Byte](16))
            client.channel.writeAndFlush(request)

            Then("The client should receive a response")
            client.observer.awaitOnNext(1, timeout)
            val response = client.observer.getOnNextEvents.get(0).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe -1L
            response.hasError shouldBe true
            response.getError.getCode shouldBe ProxyResponse.Error.Code.UNKNOWN_PROTOCOL

            client.close()
            server.close()
        }

        scenario("Server handles PING request") {
            Given("A state proxy server")
            val config = newConfig()
            val server = newServer(config)

            When("The server starts")
            server.serverChannel.await(timeout)

            And("A client connects to the server")
            val client = newClient(config)

            And("A client sending a PING request")
            val requestId = random.nextLong()
            val request = ping(requestId)
            client.channel.writeAndFlush(request)

            Then("The client should receive a PONG response")
            client.observer.awaitOnNext(1, timeout)
            val response = client.observer.getOnNextEvents.get(0).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe requestId
            response.hasPong shouldBe true
        }
    }

    feature("Test server interacts with manager") {
        scenario("Server handles subscribe and unsubscribe") {
            Given("A state proxy server with a mock manager")
            val config = newConfig()
            val manager = new MockManager
            val server = newServer(config, manager.underlying)

            When("The server starts")
            server.serverChannel.await(timeout)

            And("A client connects to the server")
            val client = newClient(config)

            Then("The manager should receive the registration")
            eventually {
                Mockito.verify(manager.underlying).register(any(), any())
            }

            And("The client should be registered")
            eventually { manager.clients should have size 1 }

            When("The client sends a SUBSCRIBE request")
            var requestId = random.nextLong()
            var request = subscribe(requestId, classOf[Network], UUID.randomUUID(),
                                    classOf[MAC], classOf[UUID], "mac_table", None)
            client.channel.writeAndFlush(request)

            Then("The manager should receive the SUBSCRIBE")
            eventually {
                Mockito.verify(manager.underlying).subscribe(any(), any(), any())
            }

            And("The client should have the subscription")
            eventually {
                manager.clients.get(client.channel.localAddress())
                       .subscriptions should have size 1
                manager.clients.get(client.channel.localAddress())
                       .subscriptions.get(1L) shouldBe request.getSubscribe
            }

            And("The client should receive a response")
            client.observer.awaitOnNext(1, timeout)
            var response = client.observer.getOnNextEvents.get(0).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe request.getRequestId
            response shouldBe acknowledge(request.getRequestId, 1L, None)

            When("The client sends an UNSUBSCRIBE request")
            requestId = random.nextLong()
            request = unsubscribe(requestId, 1L)
            client.channel.writeAndFlush(request)

            Then("The manager should receive the UNSUBSCRIBE")
            eventually {
                Mockito.verify(manager.underlying).unsubscribe(any(), any(), any())
            }

            And("The client should remove the subscription")
            manager.clients.get(client.channel.localAddress())
                   .subscriptions shouldBe empty

            And("The client should receive a response")
            client.observer.awaitOnNext(2, timeout)
            response = client.observer.getOnNextEvents.get(1).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe request.getRequestId
            response shouldBe acknowledge(request.getRequestId, 1L, None)

            When("The client closes the connection")
            client.close()

            Then("The manager should receive unregistered")
            eventually {
                Mockito.verify(manager.underlying).unregister(any())
            }

            And("The client should be unregistered")
            eventually { manager.clients shouldBe empty }

            server.close()
        }

        scenario("Server handles subscribe state table exception") {
            Given("A state proxy server with a mock manager")
            val config = newConfig()
            val e = new StateTableException(Code.SERVER_SHUTDOWN, "")
            val manager = new MockManager(
                subscribeError = Some(e), unsubscribeError = Some(e))
            val server = newServer(config, manager.underlying)

            When("The server starts")
            server.serverChannel.await(timeout)

            And("A client connects to the server")
            val client = newClient(config)

            Then("The manager should receive the registration")
            eventually {
                Mockito.verify(manager.underlying).register(any(), any())
            }

            When("The client sends a SUBSCRIBE request")
            var requestId = random.nextLong()
            var request = subscribe(requestId, classOf[Network], UUID.randomUUID(),
                                    classOf[MAC], classOf[UUID], "mac_table", None)
            client.channel.writeAndFlush(request)

            Then("The manager should receive the SUBSCRIBE")
            eventually {
                Mockito.verify(manager.underlying).subscribe(any(), any(), any())
            }

            And("The client should receive a response")
            client.observer.awaitOnNext(1, timeout)
            var response = client.observer.getOnNextEvents.get(0).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe request.getRequestId
            response shouldBe error(request.getRequestId, Code.SERVER_SHUTDOWN, "")

            When("The client sends an UNSUBSCRIBE request")
            requestId = random.nextLong()
            request = unsubscribe(requestId, 1L)
            client.channel.writeAndFlush(request)

            Then("The manager should receive the UNSUBSCRIBE")
            eventually {
                Mockito.verify(manager.underlying).unsubscribe(any(), any(), any())
            }

            And("The client should receive a response")
            client.observer.awaitOnNext(2, timeout)
            response = client.observer.getOnNextEvents.get(1).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe request.getRequestId
            response shouldBe error(request.getRequestId, Code.SERVER_SHUTDOWN, "")

            client.close()
            server.close()
        }

        scenario("Server handles subscribe client unregistered exception") {
            Given("A state proxy server with a mock manager")
            val config = newConfig()
            val e = new ClientUnregisteredException(null)
            val manager = new MockManager(
                subscribeError = Some(e), unsubscribeError = Some(e))
            val server = newServer(config, manager.underlying)

            When("The server starts")
            server.serverChannel.await(timeout)

            And("A client connects to the server")
            val client = newClient(config)

            Then("The manager should receive the registration")
            eventually {
                Mockito.verify(manager.underlying).register(any(), any())
            }

            When("The client sends a SUBSCRIBE request")
            var requestId = random.nextLong()
            var request = subscribe(requestId, classOf[Network], UUID.randomUUID(),
                                    classOf[MAC], classOf[UUID], "mac_table", None)
            client.channel.writeAndFlush(request)

            Then("The manager should receive the SUBSCRIBE")
            eventually {
                Mockito.verify(manager.underlying).subscribe(any(), any(), any())
            }

            And("The client should receive a response")
            client.observer.awaitOnNext(1, timeout)
            var response = client.observer.getOnNextEvents.get(0).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe request.getRequestId
            response shouldBe error(request.getRequestId, Code.UNKNOWN_CLIENT,
                                    e.getMessage)

            When("The client sends an UNSUBSCRIBE request")
            requestId = random.nextLong()
            request = unsubscribe(requestId, 1L)
            client.channel.writeAndFlush(request)

            Then("The manager should receive the UNSUBSCRIBE")
            eventually {
                Mockito.verify(manager.underlying).unsubscribe(any(), any(), any())
            }

            And("The client should receive a response")
            client.observer.awaitOnNext(2, timeout)
            response = client.observer.getOnNextEvents.get(1).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe request.getRequestId
            response shouldBe error(request.getRequestId, Code.UNKNOWN_CLIENT,
                                    e.getMessage)

            client.close()
            server.close()
        }

        scenario("Server handles unknown exceptions") {
            Given("A state proxy server with a mock manager")
            val config = newConfig()
            val e = new Exception
            val manager = new MockManager(
                registerError = Some(e), unregisterError = Some(e),
                subscribeError = Some(e), unsubscribeError = Some(e))
            val server = newServer(config, manager.underlying)

            When("The server starts")
            server.serverChannel.await(timeout)

            And("A client connects to the server")
            val client = newClient(config)

            Then("The manager should receive the registration")
            eventually {
                Mockito.verify(manager.underlying).register(any(), any())
            }

            When("The client sends a SUBSCRIBE request")
            var requestId = random.nextLong()
            var request = subscribe(requestId, classOf[Network], UUID.randomUUID(),
                                    classOf[MAC], classOf[UUID], "mac_table", None)
            client.channel.writeAndFlush(request)

            Then("The manager should receive the SUBSCRIBE")
            eventually {
                Mockito.verify(manager.underlying).subscribe(any(), any(), any())
            }

            When("The client sends an UNSUBSCRIBE request")
            requestId = random.nextLong()
            request = unsubscribe(requestId, 1L)
            client.channel.writeAndFlush(request)

            Then("The manager should receive the UNSUBSCRIBE")
            eventually {
                Mockito.verify(manager.underlying).unsubscribe(any(), any(), any())
            }

            When("The client closes the connection")
            client.close()

            Then("The manager should receive unregistered")
            eventually {
                Mockito.verify(manager.underlying).unregister(any())
            }

            server.close()
        }

        scenario("Server notifies clients on shutdown") {
            Given("A state proxy server with a mock manager")
            val config = newConfig()
            val manager = new MockManager
            val server = newServer(config, manager.underlying)

            When("The server starts")
            server.serverChannel.await(timeout)

            And("A first client connects to the server")
            val client1 = newClient(config)

            Then("The manager should receive the registration")
            eventually {
                Mockito.verify(manager.underlying).register(any(), any())
            }

            And("The client should be registered")
            eventually { manager.clients should have size 1 }

            When("The first client sends a SUBSCRIBE request")
            var requestId = random.nextLong()
            var request = subscribe(requestId, classOf[Network], UUID.randomUUID(),
                                    classOf[MAC], classOf[UUID], "mac_table", None)
            client1.channel.writeAndFlush(request)

            Then("The manager should receive the SUBSCRIBE")
            eventually {
                Mockito.verify(manager.underlying).subscribe(any(), any(), any())
            }

            And("The first client should receive a response")
            client1.observer.awaitOnNext(1, timeout)
            var response = client1.observer.getOnNextEvents.get(0).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe request.getRequestId
            response shouldBe acknowledge(request.getRequestId, 1L, None)

            When("A second client connects to the server")
            val client2 = newClient(config)

            Then("The manager should receive the registration")
            eventually {
                Mockito.verify(manager.underlying, times(2)).register(any(), any())
            }

            And("The client should be registered")
            eventually { manager.clients should have size 2 }

            When("The second client sends a SUBSCRIBE request")
            requestId = random.nextLong()
            request = subscribe(requestId, classOf[Network], UUID.randomUUID(),
                                classOf[MAC], classOf[UUID], "mac_table", None)
            client2.channel.writeAndFlush(request)

            Then("The manager should receive the SUBSCRIBE")
            eventually {
                Mockito.verify(manager.underlying, times(2))
                       .subscribe(any(), any(), any())
            }

            And("The second client should receive a response")
            client2.observer.awaitOnNext(1, timeout)
            response = client2.observer.getOnNextEvents.get(0).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe request.getRequestId
            response shouldBe acknowledge(request.getRequestId, 2L, None)

            When("The first client sends a second SUBSCRIBE request")
            requestId = random.nextLong()
            request = subscribe(requestId, classOf[Network], UUID.randomUUID(),
                                classOf[MAC], classOf[UUID], "mac_table", None)
            client1.channel.writeAndFlush(request)

            Then("The manager should receive the SUBSCRIBE")
            eventually {
                Mockito.verify(manager.underlying, times(3))
                       .subscribe(any(), any(), any())
            }

            And("The first client should receive a response")
            client1.observer.awaitOnNext(2, timeout)
            response = client1.observer.getOnNextEvents.get(1).asInstanceOf[ProxyResponse]
            response.getRequestId shouldBe request.getRequestId
            response shouldBe acknowledge(request.getRequestId, 3L, None)

            When("The manager closes")
            manager.underlying.close()

            Then("The first client should receive two completed responses")
            client1.observer.awaitOnNext(4, timeout)
            response = client1.observer.getOnNextEvents.get(2).asInstanceOf[ProxyResponse]
            response.hasNotify shouldBe true
            response = client1.observer.getOnNextEvents.get(3).asInstanceOf[ProxyResponse]
            response.hasNotify shouldBe true

            And("The second client should receive a completed response")
            client2.observer.awaitOnNext(2, timeout)
            response = client2.observer.getOnNextEvents.get(1).asInstanceOf[ProxyResponse]
            response.hasNotify shouldBe true

            server.close()
            client1.close()
            client2.close()
        }
    }
}
