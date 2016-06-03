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

import java.util.concurrent.Executor

import com.google.protobuf.Message
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler, nio}
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}
import rx.Observer

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import org.midonet.cluster.rpc.State

@RunWith(classOf[JUnitRunner])
class ConnectionTest extends FeatureSpec with Matchers {

    class FakeNetty(target: SimpleChannelInboundHandler[Message],
                    ctx: ChannelHandlerContext) {

        def connect(): Unit = target.handlerAdded(ctx)
        def close(): Unit = target.channelInactive(ctx)
        def error(cause: Throwable): Unit = target.exceptionCaught(ctx,cause)
        def receive(msg: Message): Unit = target.channelRead(ctx,msg)
    }

    class MockObjects(host: String = MockObjects.DEFAULT_HOST,
                      port: Int = MockObjects.DEFAULT_PORT)
                     (implicit eventLoopGroup: NioEventLoopGroup) {
        val observer = Mockito.mock(classOf[Observer[Message]])
        val ctx = Mockito.mock(classOf[ChannelHandlerContext])
        val client = new Connection(host,
                                    port,
                                    observer,
                                    State.Message.getDefaultInstance,
                                    eventLoopGroup)
        val netty = new FakeNetty(client,ctx)
    }

    object MockObjects {
        val DEFAULT_HOST : String = "localhost"
        val DEFAULT_PORT : Int = 12345
    }

    val executor = new Executor {
        override def execute(runnable: Runnable): Unit = runnable.run()
    }
    implicit val exctx = ExecutionContext.fromExecutor(executor)

    // not used as the event loop from Netty is simulated
    implicit val eventLoopGroup = new nio.NioEventLoopGroup(2)

    val testMsgBuilder = State.Message.Refresh.newBuilder()

    feature("connection behavior") {
        val msg = testMsgBuilder.setSubscriptionId(0).build()

        scenario("not connected") {
            val test = new MockObjects
            test.client.isConnected should be (false)
            // out of place receive, must be ignored
            test.netty.receive(msg)
            Mockito.verifyZeroInteractions(test.observer)
        }

        scenario("connected") {
            val test = new MockObjects
            test.client.connect // unneeded, it' here to improve coverage
            // simulating successful connect by netty:
            test.netty.connect()
            test.client.isConnected should be (true)
            Mockito.verifyZeroInteractions(test.observer)
        }

        scenario("data flow") {
            val test = new MockObjects
            test.netty.connect()
            test.netty.receive(msg)
            test.netty.receive(msg)
            test.netty.receive(msg)
            test.netty.close()
            test.netty.close() // Observer must complete just once
            Mockito.verify(test.observer,Mockito.times(3)).onNext(msg)
            Mockito.verify(test.observer).onCompleted()
            Mockito.verifyNoMoreInteractions(test.observer)
        }

        val exception = new Exception("Test exception")

        scenario("no errors until connected") {
            val test = new MockObjects
            test.netty.error(exception)
            Mockito.verifyNoMoreInteractions(test.observer)
        }

        scenario("No close after error") {
            val test = new MockObjects
            test.netty.connect()
            test.netty.receive(msg)
            test.netty.error(exception)
            test.netty.close() // must not be propagated to the observer
            Mockito.verify(test.observer).onNext(msg)
            Mockito.verify(test.observer).onError(exception)
            Mockito.verifyNoMoreInteractions(test.observer)
        }

        scenario("sending data") {
            val test = new MockObjects
            test.netty.connect()
            test.client.write(msg,flush=false)
            test.client.write(msg) // implicit flush
            test.client.write(msg,flush=false)
            test.client.flush()
            Mockito.verify(test.ctx,Mockito.times(2)).write(msg)
            Mockito.verify(test.ctx).writeAndFlush(msg)
            Mockito.verify(test.ctx).flush()
        }

        scenario("user close") {
            val test = new MockObjects
            test.netty.connect()
            test.client.close()
            test.client.close() // ignored
            Mockito.verify(test.ctx).close()
        }

        //used for manual test using netcat
        ignore("real connection") {
            val test = new MockObjects
            val c = new Connection("localhost",1234,test.observer,
                                   State.Message.getDefaultInstance,
                                   new nio.NioEventLoopGroup(2))
            Await.ready(c.connect(),3 seconds).isCompleted shouldBe true
            c.write(msg,flush=false)
            c.flush()
            Thread.sleep(5000)
            //c.close()
            Mockito.verify(test.observer,Mockito.times(1)).onCompleted()
            Mockito.verify(test.observer,Mockito.times(0)).onError(_)
            Mockito.verify(test.observer,Mockito.times(1)).onNext(msg)
        }
    }
}
