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

package org.midonet.brain.api.services

import com.google.protobuf.Message
import io.netty.channel.ChannelHandlerContext
import org.mockito.Mockito
import org.scalatest.{FeatureSpec, Matchers}
import rx.Observer

import org.midonet.cluster.rpc.Commands

class ConnectionTest extends FeatureSpec with Matchers {

    class TestLoopProtocolFactory(val ok: Message) extends ProtocolFactory {
        class Loop(val output: Observer[Message]) extends State {
            override def process: Process = {
                case input =>
                    output.onNext(ok)
                    this
            }
        }
        override def start(output: Observer[Message]): State = {
            new Loop(output)
        }
    }

    feature("connection behavior")
    {
        scenario("connection creation") {
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val cMgr = Mockito.mock(classOf[ConnectionManager])
            val protocol = Mockito.mock(classOf[ProtocolFactory])
            val conn = new Connection(ctx, protocol)(cMgr)
        }

        scenario("normal message processing") {
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val cMgr = Mockito.mock(classOf[ConnectionManager])
            val req = Commands.Request.getDefaultInstance
            val ack = Commands.Response.Ack.getDefaultInstance
            val protocol = new TestLoopProtocolFactory(ack)

            val conn = new Connection(ctx, protocol)(cMgr)
            Mockito.verify(ctx, Mockito.never()).writeAndFlush(ack)

            conn.msg(req)
            conn.msg(req)
            conn.msg(req)
            Mockito.verify(ctx, Mockito.times(3)).writeAndFlush(ack)
            Mockito.verify(ctx, Mockito.never()).close()
            Mockito.verifyZeroInteractions(cMgr)
        }

        scenario("error processing") {
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val cMgr = Mockito.mock(classOf[ConnectionManager])
            val exc = Mockito.mock(classOf[Throwable])
            val ack = Commands.Response.Ack.getDefaultInstance
            val protocol = new TestLoopProtocolFactory(ack)

            val conn = new Connection(ctx, protocol)(cMgr)
            Mockito.verify(ctx, Mockito.never()).writeAndFlush(ack)

            conn.error(exc)
            conn.error(exc)
            conn.error(exc)
            Mockito.verify(ctx, Mockito.times(3)).writeAndFlush(ack)
            Mockito.verify(ctx, Mockito.never()).close()
            Mockito.verifyZeroInteractions(cMgr)
        }

        scenario("termination") {
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val cMgr = Mockito.mock(classOf[ConnectionManager])
            val ack = Commands.Response.Ack.getDefaultInstance
            val protocol = new TestLoopProtocolFactory(ack)

            val conn = new Connection(ctx, protocol)(cMgr)
            Mockito.verify(ctx, Mockito.never()).writeAndFlush(ack)

            conn.disconnect()
            conn.disconnect()
            conn.disconnect()
            Mockito.verify(ctx, Mockito.times(1)).writeAndFlush(ack)
            Mockito.verify(ctx, Mockito.times(1)).close()
            Mockito.verify(cMgr, Mockito.times(1)).unregister(ctx)
        }
    }
}


