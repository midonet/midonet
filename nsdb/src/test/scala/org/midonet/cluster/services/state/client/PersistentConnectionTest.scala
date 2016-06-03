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
import scala.concurrent.{Await, ExecutionContext, Promise}

import com.google.protobuf.Message

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import rx.Observer

import org.midonet.cluster.rpc.State.ProxyRequest

import io.netty.channel.nio
import io.netty.channel.nio.NioEventLoopGroup

@RunWith(classOf[JUnitRunner])
class PersistentConnectionTest  extends FeatureSpec with Matchers {

    val executor = new ScheduledThreadPoolExecutor(4)
    implicit val exctx = ExecutionContext.fromExecutor(executor)

    val (host,port) = ("localhost",0) // not used
    val numThreads = 2
    val timeoutMs = 100 milliseconds
    val loopGroup = new nio.NioEventLoopGroup(numThreads)
    val mockConnection = Mockito.mock(classOf[Connection])
    val message = ProxyRequest.newBuilder()
                                .setRequestId(1)
                                .setPing(ProxyRequest.Ping.getDefaultInstance)
                                .build()

    class TestConnection() extends PersistentConnection(host,port,loopGroup,executor,timeoutMs) {

        var numMessages = 0
        var numConnects = 0
        var numDisconnects = 0

        override protected def connectionFactory(host: String,
                                       port: Int,
                                       subscriber: Observer[Message],
                                       eventLoopGroup: NioEventLoopGroup)
                                       (implicit ec: ExecutionContext)
                : Connection = mockConnection

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

    feature("connect behavior") {
        ignore("manual test") {
            val c = new TestConnection
            Await.ready(c.start(),10 seconds).isCompleted shouldBe true
            c.write(message) shouldBe true
            Thread.sleep(10000)
            c.write(message) shouldBe true
            c.close() shouldBe true
        }
        scenario("connect is triggered after subscribe") {
            val c = new TestConnection
            val connectPromise = Promise[Connection]()
            val observer = c : Observer[Message]
            Mockito.when(mockConnection.connect()).thenReturn(connectPromise.future)
            connectPromise.success(mockConnection)
            Await.ready(c.start(),1 seconds).isCompleted should be (true)
            observer.onNext(message)
            observer.onNext(message)
            Thread.sleep(timeoutMs.toMillis * 2)
            observer.onCompleted()
            Thread.sleep(timeoutMs.toMillis * 2)
            observer.onNext(message)
            observer.onError(new Exception)
            Thread.sleep(timeoutMs.toMillis * 2)
            c.stop()
            c.numMessages shouldBe 3
            c.numConnects shouldBe 3
            c.numDisconnects shouldBe 3
        }
    }


}
