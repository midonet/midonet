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
import org.midonet.cluster.rpc.Commands

import io.netty.channel.ChannelHandlerContext
import org.mockito.Mockito
import org.mockito.Matchers._
import org.scalatest.{FeatureSpec, Matchers}


class RequestHandlerTest extends FeatureSpec with Matchers {

    feature("process incoming communication events")
    {
        scenario("forwarding connect event") {
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val conn = Mockito.mock(classOf[Connection])
            val cMgr = Mockito.mock(classOf[ConnectionManager])
            Mockito.stub(cMgr.get(ctx)).toReturn(conn)

            val reqHandler = new RequestHandler(cMgr)
            reqHandler.subject.onNext(Connect(ctx))
            Mockito.verifyZeroInteractions(conn)
        }

        scenario("forwarding disconnect event") {
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val conn = Mockito.mock(classOf[Connection])
            val cMgr = Mockito.mock(classOf[ConnectionManager])
            Mockito.stub(cMgr.get(ctx)).toReturn(conn)

            val reqHandler = new RequestHandler(cMgr)
            reqHandler.subject.onNext(Disconnect(ctx))
            Mockito.verify(conn, Mockito.times(1)).disconnect()
            Mockito.verify(conn, Mockito.never()).error(anyObject[Throwable]())
            Mockito.verify(conn, Mockito.never()).msg(anyObject[Message]())
            Mockito.verify(cMgr, Mockito.times(1)).get(ctx)
        }

        scenario("forwarding error event") {
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val conn = Mockito.mock(classOf[Connection])
            val cMgr = Mockito.mock(classOf[ConnectionManager])
            val exc = Mockito.mock(classOf[Throwable])
            Mockito.stub(cMgr.get(ctx)).toReturn(conn)

            val reqHandler = new RequestHandler(cMgr)
            reqHandler.subject.onNext(Error(ctx, exc))
            Mockito.verify(conn, Mockito.never()).disconnect()
            Mockito.verify(conn, Mockito.times(1)).error(exc)
            Mockito.verify(conn, Mockito.never()).msg(anyObject[Message]())
            Mockito.verify(cMgr, Mockito.times(1)).get(ctx)
        }

        scenario("forwarding message") {
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val conn = Mockito.mock(classOf[Connection])
            val cMgr = Mockito.mock(classOf[ConnectionManager])
            val msg = Commands.Request.getDefaultInstance
            Mockito.stub(cMgr.get(ctx)).toReturn(conn)

            val reqHandler = new RequestHandler(cMgr)
            reqHandler.subject.onNext(Request(ctx, msg))
            Mockito.verify(conn, Mockito.never()).disconnect()
            Mockito.verify(conn, Mockito.never()).error(anyObject[Throwable]())
            Mockito.verify(conn, Mockito.times(1)).msg(msg)
            Mockito.verify(cMgr, Mockito.times(1)).get(ctx)
        }
    }
}


