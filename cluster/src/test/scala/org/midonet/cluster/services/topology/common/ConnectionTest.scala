/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.cluster.services.topology.common

import java.util.concurrent.Executor

import io.netty.util.concurrent.GenericFutureListener

import scala.concurrent.{ExecutionContext, Future, Promise}

import com.google.protobuf.Message

import org.junit.runner.RunWith
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import rx.{Observer, Subscription}

import org.midonet.cluster.rpc.Commands

import io.netty.channel.{ChannelFuture, ChannelHandlerContext}

@RunWith(classOf[JUnitRunner])
class ConnectionTest extends FeatureSpec with Matchers {

    class TestLoopProtocolFactory(val ok: Message) extends ProtocolFactory {
        class Loop(val output: Observer[Message]) extends State {
            override def process(msg: Any): State = msg match {
                case input =>
                    output.onNext(ok)
                    this
            }
        }
        override def start(output: Observer[Message]): State =
            new Loop(output)
    }

    feature("connection behavior")
    {
        scenario("connection creation") {
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val cMgr = Mockito.mock(classOf[ConnectionManager])
            val protocol = Mockito.mock(classOf[ProtocolFactory])
            val start = Mockito.mock(classOf[State])
            Mockito.when(
                protocol.start(org.mockito.Matchers.anyObject[Observer[Message]])
            ).thenReturn(start)
            val conn = new Connection(ctx, protocol)(cMgr)
        }

        scenario("normal message processing") {
            val senderFactory = Mockito.mock(classOf[ConnectionSenderFactory])
            val sender = Mockito.mock(classOf[ConnectionSender])
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val cMgr = Mockito.mock(classOf[ConnectionManager])
            val req = Commands.Request.getDefaultInstance
            val ack = Commands.Response.Ack.getDefaultInstance
            val protocol = new TestLoopProtocolFactory(ack)
            Mockito.when(senderFactory.get(ctx)).thenReturn(sender)

            val conn = new Connection(ctx, protocol, senderFactory)(cMgr)
            Mockito.verify(sender, Mockito.never()).send(ack)
            Mockito.verifyZeroInteractions(ctx)

            conn.msg(req)
            conn.msg(req)
            conn.msg(req)
            Mockito.verify(sender, Mockito.times(3)).send(ack)
            Mockito.verify(ctx, Mockito.never()).close()
            Mockito.verifyZeroInteractions(cMgr)
        }

        scenario("error processing") {
            val senderFactory = Mockito.mock(classOf[ConnectionSenderFactory])
            val sender = Mockito.mock(classOf[ConnectionSender])
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val cMgr = Mockito.mock(classOf[ConnectionManager])
            val exc = Mockito.mock(classOf[Throwable])
            val ack = Commands.Response.Ack.getDefaultInstance
            val protocol = new TestLoopProtocolFactory(ack)
            Mockito.when(senderFactory.get(ctx)).thenReturn(sender)

            val conn = new Connection(ctx, protocol, senderFactory)(cMgr)
            Mockito.verifyZeroInteractions(ctx)
            Mockito.verify(sender, Mockito.never()).send(ack)

            conn.error(exc)
            conn.error(exc)
            conn.error(exc)
            Mockito.verify(sender, Mockito.times(3)).send(ack)
            Mockito.verify(ctx, Mockito.never()).close()
            Mockito.verifyZeroInteractions(cMgr)
        }

        scenario("termination") {
            val senderFactory = Mockito.mock(classOf[ConnectionSenderFactory])
            val sender = Mockito.mock(classOf[ConnectionSender])
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val cMgr = Mockito.mock(classOf[ConnectionManager])
            val ack = Commands.Response.Ack.getDefaultInstance
            val protocol = new TestLoopProtocolFactory(ack)
            Mockito.when(senderFactory.get(ctx)).thenReturn(sender)

            val conn = new Connection(ctx, protocol, senderFactory)(cMgr)
            Mockito.verifyZeroInteractions(ctx)
            Mockito.verify(sender, Mockito.never()).send(ack)

            conn.disconnect()
            conn.disconnect()
            conn.disconnect()
            Mockito.verify(sender, Mockito.times(1)).send(ack)
            Mockito.verify(sender, Mockito.times(1)).directFlush()
            Mockito.verify(ctx, Mockito.times(1)).close()
            Mockito.verify(cMgr, Mockito.times(1)).unregister(ctx)
        }
    }

    feature("connection sender")
    {
        val writeExecutor = new Executor {
            override def execute(runnable: Runnable): Unit = runnable.run()
        }
        val writeEC = ExecutionContext.fromExecutor(writeExecutor)

        scenario("sender creation - direct operations")
        {
            val msg = Mockito.mock(classOf[Message])
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val channel = Mockito.mock(classOf[ChannelFuture])
            Mockito.when(ctx.write(msg)).thenReturn(channel)
            Mockito.when(ctx.writeAndFlush(msg)).thenReturn(channel)

            val sender = new ConnectionSender(ctx)
            Mockito.verifyZeroInteractions(ctx)

            sender.directSend(msg)
            Mockito.verify(ctx, Mockito.times(1)).write(msg)
            Mockito.verifyNoMoreInteractions(ctx)

            sender.directSendAndFlush(msg)
            Mockito.verify(ctx, Mockito.times(1)).writeAndFlush(msg)
            Mockito.verifyNoMoreInteractions(ctx)

            sender.directFlush()
            Mockito.verify(ctx, Mockito.times(1)).flush()
            Mockito.verifyNoMoreInteractions(ctx)
        }

        scenario("sender operation - immediate start")
        {
            val msg = Mockito.mock(classOf[Message])
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val channel = Mockito.mock(classOf[ChannelFuture])
            Mockito.when(ctx.write(msg)).thenReturn(channel)
            Mockito.when(ctx.writeAndFlush(msg)).thenReturn(channel)

            val startNow = Promise[Boolean]().success(true).future
            val sender = new ConnectionSender(ctx, startNow, writeEC)
            Mockito.verifyZeroInteractions(ctx)

            sender.send(msg)
            Mockito.verify(ctx, Mockito.times(1)).write(msg)
            Mockito.verifyNoMoreInteractions(ctx)
        }

        scenario("sender operation - delayed - successful start")
        {
            val msg = Mockito.mock(classOf[Message])
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val channel = Mockito.mock(classOf[ChannelFuture])
            Mockito.when(ctx.write(msg)).thenReturn(channel)
            Mockito.when(ctx.writeAndFlush(msg)).thenReturn(channel)

            val start = Promise[Boolean]()
            val sender = new ConnectionSender(ctx, start.future, writeEC)
            Mockito.verifyZeroInteractions(ctx)

            sender.send(msg)
            Mockito.verifyZeroInteractions(ctx)

            start.success(true)
            Mockito.verify(ctx, Mockito.times(1)).write(msg)
            Mockito.verifyNoMoreInteractions(ctx)
        }

        scenario("sender operation - delayed - failed start")
        {
            val msg = Mockito.mock(classOf[Message])
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val channel = Mockito.mock(classOf[ChannelFuture])
            Mockito.when(ctx.write(msg)).thenReturn(channel)
            Mockito.when(ctx.writeAndFlush(msg)).thenReturn(channel)

            val start = Promise[Boolean]()
            val sender = new ConnectionSender(ctx, start.future, writeEC)
            Mockito.verifyZeroInteractions(ctx)

            sender.send(msg)
            Mockito.verifyZeroInteractions(ctx)

            start.failure(new Exception("some exception"))
            Mockito.verifyZeroInteractions(ctx)
        }

        scenario("sender operation - multiple operations")
        {
            val msg = Mockito.mock(classOf[Message])
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val channel = Mockito.mock(classOf[ChannelFuture])
            Mockito.when(ctx.write(msg)).thenReturn(channel)
            Mockito.when(ctx.writeAndFlush(msg)).thenReturn(channel)
            Mockito.when(channel.isSuccess).thenReturn(true)
            val cb1 = ArgumentCaptor.forClass(
                classOf[GenericFutureListener[ChannelFuture]])
            val cb2 = ArgumentCaptor.forClass(
                classOf[GenericFutureListener[ChannelFuture]])

            val start = Promise[Boolean]()
            val sender = new ConnectionSender(ctx, start.future, writeEC)
            Mockito.verifyZeroInteractions(ctx)

            val done1 = sender.send(msg)
            Mockito.verifyZeroInteractions(ctx)
            Mockito.verifyZeroInteractions(channel)

            val done2 = sender.send(msg)
            Mockito.verifyZeroInteractions(ctx)
            Mockito.verifyZeroInteractions(channel)
            done1.isCompleted should be (false)
            done2.isCompleted should be (false)

            start.success(true)
            Mockito.verify(ctx, Mockito.times(1)).write(msg)
            Mockito.verify(channel, Mockito.times(1)).addListener(cb1.capture())
            Mockito.verifyNoMoreInteractions(ctx)
            Mockito.verifyNoMoreInteractions(channel)
            done1.isCompleted should be (false)
            done2.isCompleted should be (false)

            cb1.getValue.operationComplete(channel)
            Mockito.verify(ctx, Mockito.times(2)).write(msg)
            Mockito.verify(channel, Mockito.times(2)).addListener(cb2.capture())
            Mockito.verify(channel, Mockito.times(1)).isSuccess
            Mockito.verifyNoMoreInteractions(ctx)
            Mockito.verifyNoMoreInteractions(channel)
            done1.isCompleted should be (true)
            done2.isCompleted should be (false)

            cb2.getValue.operationComplete(channel)
            Mockito.verifyZeroInteractions(ctx)
            Mockito.verify(channel, Mockito.times(2)).isSuccess
            Mockito.verifyNoMoreInteractions(channel)
            done1.isCompleted should be (true)
            done2.isCompleted should be (true)

        }

    }
}


