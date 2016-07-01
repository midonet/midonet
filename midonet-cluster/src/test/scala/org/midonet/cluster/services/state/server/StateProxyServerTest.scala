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

import java.net.{BindException, ServerSocket}

import scala.util.Random
import scala.concurrent.duration._
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
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import rx.subjects.PublishSubject

import org.midonet.cluster.StateProxyConfig
import org.midonet.cluster.rpc.State.{KeyValue, ProxyRequest, ProxyResponse}
import org.midonet.util.concurrent._
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class StateProxyServerTest extends FeatureSpec with Matchers
                           with GivenWhenThen {

    private class TestClient(port: Int) extends ChannelInboundHandlerAdapter {

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

    private val random = new Random()
    private val timeout = 30 seconds

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
        val port = localPort
        new StateProxyConfig(ConfigFactory.parseString(
            s"""
               |cluster.state_proxy.server.address : 0.0.0.0
               |cluster.state_proxy.server.port : $port
               |cluster.state_proxy.server.supervisor_threads : $supervisorThreads
               |cluster.state_proxy.server.worker_threads : $workerThreads
               |cluster.state_proxy.server.max_pending_connections : $maxPendingConnections
               |cluster.state_proxy.server.bind_retry_interval : ${bindRetryInterval}s
               |cluster.state_proxy.server.channel_timeout : 15s
               |cluster.state_proxy.server.shutdown_quiet_period : 0s
               |cluster.state_proxy.server.shutdown_timeout : 15s
             """.stripMargin))
    }

    private def newServer(config: StateProxyConfig): StateProxyServer = {
        new StateProxyServer(config)
    }

    private def newClient(config: StateProxyConfig): TestClient = {
        new TestClient(config.serverPort)
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
    }
}
