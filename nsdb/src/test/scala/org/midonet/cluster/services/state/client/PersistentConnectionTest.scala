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

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

import com.google.protobuf.Message

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.rpc.State.ProxyRequest

import io.netty.channel.nio.NioEventLoopGroup

@RunWith(classOf[JUnitRunner])
class PersistentConnectionTest  extends FeatureSpec with Matchers {

    val executor = new ScheduledThreadPoolExecutor(4)
    implicit val exctx = ExecutionContext.fromExecutor(executor)

    val numThreads = 4
    val reconnectTimeout = 100 milliseconds
    implicit val loopGroup = new NioEventLoopGroup(numThreads)
    val message = msgPing(1)

    private def msgPing(id: Long) = ProxyRequest.newBuilder()
                                    .setRequestId(id)
                                    .setPing(ProxyRequest.Ping.getDefaultInstance)
                                    .build()

    class TestConnection(val server: TestServer)
        extends PersistentConnection("localhost",
                                     server.port,
                                     executor,
                                     reconnectTimeout) {

        var numMessages = 0
        var numConnects = 0
        var numDisconnects = 0

        override protected def onNext(msg: Message): Unit = {
            numMessages += 1
        }

        override protected def onConnect(): Unit = {
            numConnects += 1
        }

        override protected def onDisconnect(cause: Throwable): Unit = {
            numDisconnects += 1
        }
    }

    feature("disconnected behavior") {
        val server = new TestServer(ProxyRequest.getDefaultInstance)
        val c = new TestConnection(server)
        scenario("no activity until started") {
            c.write(message,flush=true) shouldBe false
            c.write(message,flush=false) shouldBe false
            c.flush() shouldBe false
            c.close() shouldBe false
            c.numConnects shouldBe 0
            c.numMessages shouldBe 0
            c.numDisconnects shouldBe 0
        }
    }

    feature("started behavior") {
        val server = new TestServer(ProxyRequest.getDefaultInstance)
        val c = new TestConnection(server)

        scenario("start should complete") {
            val startFuture = c.start()
            Await.ready(startFuture, 1 second).isCompleted should be(true)
            ScalaFutures.whenReady(startFuture) { case _ => }
            val serverFuture = server.clientConnected()
            Await.ready(serverFuture,1 second).isCompleted should be (true)
            ScalaFutures.whenReady(serverFuture) { case _ => }
        }

        scenario("reconnection should be automatic") {
            server.close()
            // give the timeout time to propagate to local connection
            Thread.sleep(2 * reconnectTimeout.toMillis)
            c.numConnects shouldBe 2
            c.numDisconnects shouldBe 1
            c.numMessages shouldBe 0
        }
        scenario("Messages are received on the other end") {
            val serverObserverMock = Mockito.mock(classOf[Connection.Observer])
            server.attachedObserver = serverObserverMock
            assert(c.write(message))
            Mockito.verify(serverObserverMock,
                           Mockito.timeout(1000)).onNext(message)
            c.close()
        }
    }
}
