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

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import com.google.protobuf.Message

import io.netty.channel.nio.NioEventLoopGroup

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import rx.Observer

import org.midonet.cluster.rpc.State.{ProxyRequest, ProxyResponse}
import org.midonet.cluster.services.discovery.MidonetServiceHostAndPort
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class PersistentConnectionTest extends FeatureSpec
                                       with Matchers
                                       with MidonetEventually
                                       with GivenWhenThen {

    val executor = new ScheduledThreadPoolExecutor(4)
    implicit val exctx = ExecutionContext.fromExecutor(executor)

    val numThreads = 4
    val connectTimeout = 5 seconds
    val readTimeout = 10 seconds
    val reconnectTimeout = 300 milliseconds
    implicit val loopGroup = new NioEventLoopGroup(numThreads)
    val message = msgPing(1)

    private def msgPing(id: Long) = ProxyRequest.newBuilder()
        .setRequestId(id)
        .setPing(ProxyRequest.Ping.getDefaultInstance)
        .build()

    class TestConnection(port: Int)
        extends PersistentConnection[ProxyRequest, ProxyResponse](
            "Test Connection",
            executor,
            connectTimeout,
            readTimeout) {

        var numMessages = 0
        var numConnects = 0
        var numDisconnects = 0

        override protected def onFailedConnection(cause: Throwable): Unit = {
        }

        override protected def reconnectionDelay: Duration = reconnectTimeout

        override protected def getMessagePrototype = ProxyResponse
            .getDefaultInstance

        override protected def getRemoteAddress =
            Some(MidonetServiceHostAndPort("localhost", port))

        override protected def onNext(msg: ProxyResponse): Unit = {
            numMessages += 1
            log debug s"test: onNext #$numMessages"
        }

        override protected def onConnect(): Unit = {
            numConnects += 1
            log debug s"test: onConnect #$numConnects"
        }

        override protected def onDisconnect(cause: Throwable): Unit = {
            numDisconnects += 1
            log debug s"test: onDisconnect #$numDisconnects"
        }
    }

    feature("no activity until started") {

        Given("A stopped connection")
        val c = new TestConnection(0)

        scenario("I/O without start") {

            When("output is attempted")
            val writeFlushResult = c.write(message, flush = true)
            val writeNoFlushResult = c.write(message, flush = false)
            val flushResult = c.flush()

            Then("It had no effect")
            writeFlushResult shouldBe false
            writeNoFlushResult shouldBe false
            flushResult shouldBe false
            c.numConnects shouldBe 0
            c.numMessages shouldBe 0
            c.numDisconnects shouldBe 0
        }
    }

    feature("not usable after stop") {

        Given("A stopped connection")
        val c = new TestConnection(0)

        scenario("Start after stop") {
            When("it is stopped")
            c.stop()
            Then("When start is called, it throws StoppedException")
            an[PersistentConnection.StoppedException] shouldBe thrownBy {
                c.start()
            }
            And("It can't be used")
            c.write(message, flush = true) shouldBe false
            c.write(message, flush = false) shouldBe false
            c.flush() shouldBe false
            c.numConnects shouldBe 0
            c.numMessages shouldBe 0
            c.numDisconnects shouldBe 0
        }
    }

    feature("Can be connected to a server") {

        Given("A connection and a server")
        val server = new TestServer(ProxyRequest.getDefaultInstance)
        val c = new TestConnection(server.address.port)

        scenario("connect using start") {

            When("The connection is started")
            c.start()

            Then("It connects to the server")
            eventually {
                c.isConnected shouldBe true
                server.hasClient shouldBe true
            }
        }

        scenario("server close") {

            When("The server closes its end")
            server.close()

            Then("The close is received at the local end")
            eventually {
                c.isConnected shouldBe false
            }

            And("No action until the timeout is completed")
            Thread.sleep(reconnectTimeout.toMillis / 2)
            c.isConnected shouldBe false

            And("It reconnects when the timeout expires")
            eventually {
                c.isConnected shouldBe true
                c.numConnects shouldBe 2
            }

            c.numDisconnects shouldBe 1
            c.numMessages shouldBe 0

            c.stop()
            eventually {
                c.isConnected shouldBe false
            }
            eventually {
                server.hasClient shouldBe false
            }
        }
    }

    feature("reconnects on error") {
        val server = new TestServer(ProxyRequest.getDefaultInstance)
        val c = new TestConnection(server.address.port) {
            override def onNext(msg: ProxyResponse): Unit = {
                throw new Exception
            }
        }
        c.start()

        scenario("disconnect on error") { for (i <- 1 to 1) {

            eventually {
                server.hasClient shouldBe true
                c.isConnected shouldBe true
            }
            server.write(message)
            eventually {
                c.isConnected shouldBe false
            }
            c.numDisconnects shouldBe i
            eventually { server.hasClient shouldBe false }
        } }

    }
    feature("reconnection is aborted by close") {

        val server = new TestServer(ProxyRequest.getDefaultInstance)

        scenario("close while reconnecting") {

            Given("A connection to a server")
            val c = new TestConnection(server.address.port)

            When("It is connected")
            c.start()
            eventually {
                c.isConnected shouldBe true
            }
            eventually {
                server.hasClient shouldBe true
            }

            And("Forced to reconnect")
            server.close()
            Thread.sleep(reconnectTimeout.toMillis / 2)
            c.isConnected shouldBe false

            And("it is stopped in the middle of a reconnect")
            c.stop()

            Then("It will not reconnect")
            Thread.sleep(2 * reconnectTimeout.toMillis)
            c.numDisconnects shouldBe 1
            c.numConnects shouldBe 1
            eventually {
                server.hasClient shouldBe false
            }
        }

        scenario("failure to connect") {
            for (i <- 1 to 1) {
                val c = new TestConnection(0)
                c.start()
                eventually {
                    c.isConnecting shouldBe true
                }
                c.stop()
                eventually {
                    c.isConnecting shouldBe false
                }
                Thread.sleep(reconnectTimeout.toMillis)
                c.isConnecting shouldBe false
                c.isConnected shouldBe false
            }
        }
    }

    feature("Behavior is deterministic when run in parallel") {

        val server = new TestServer(ProxyRequest.getDefaultInstance)

        scenario("multiple starts in parallel") {

            Given("A disconnected client")
            val c = new TestConnection(server.address.port)

            When("Start is called in parallel")
            val tries = 50
            val succeeded = new AtomicInteger(0)
            val failed = new AtomicInteger(0)
            for (i <- 1 to tries) {
                Future {
                    c.start()
                } onComplete {
                    case Success(_) => succeeded.incrementAndGet()
                    case Failure(err) =>
                        failed.incrementAndGet()
                        err shouldBe
                        an[PersistentConnection.AlreadyStartedException]
                }
            }

            Then("Only one call succeeds")
            eventually {
                (succeeded.get + failed.get) shouldBe tries
            }
            assert(succeeded.get == 1)
        }
    }

    feature("I/O works") {

        val server = new TestServer(ProxyRequest.getDefaultInstance)
        val c = new TestConnection(server.address.port)

        val serverObserverMock = Mockito
            .mock(classOf[Observer[Message]])
        server.attachedObserver = serverObserverMock

        c.start()
        eventually {
            c.isConnected shouldBe true
            server.hasClient shouldBe true
        }

        scenario("send message") {
            for (count <- 1 to 1) {
                assert(c.write(message))
                Mockito.verify(serverObserverMock,
                               Mockito.timeout(1000)).onNext(message)
            }
        }

        scenario("receive message") {
            for (count <- 1 to 1) {
                server.write(message)
                eventually {
                    c.numMessages shouldBe count
                }
            }
        }
    }

    feature("observer events are issued") {

        val server = new TestServer(ProxyRequest.getDefaultInstance)

        scenario("onConnect/onDisconnect") {
            for (count <- 1 to 1) {

                Given("A persistent connection")
                val c = new TestConnection(server.address.port)

                When("It is established")
                c.start()

                Then("connect and disconnect callbacks are fired")
                val iters = 5
                for (i <- 1 until iters) {
                    eventually {
                        c.numConnects shouldBe i
                        server.hasClient shouldBe true
                    }
                    server.close()
                    eventually {
                        c.numDisconnects shouldBe i
                        server.hasClient shouldBe false
                    }
                }
                eventually {
                    c.numConnects shouldBe iters
                    server.hasClient shouldBe true
                }

                And("When stopped manually, disconnect not fired")
                c.stop()
                eventually {
                    c.numDisconnects shouldBe iters
                    server.hasClient shouldBe false
                }
            }
        }
    }
}
