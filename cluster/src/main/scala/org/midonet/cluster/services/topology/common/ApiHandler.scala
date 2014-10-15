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

import com.google.protobuf.Message

import org.slf4j.LoggerFactory

import rx.Observer

import org.midonet.cluster.rpc.Commands

import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.util.ReferenceCountUtil

/**
 * Protocol buffer message handler.
 * It reports connection establishments and disconnections, and
 * forwards received protobufs to the given observer.
 * Note that onComplete/onError is never called on the observer, as it
 * may be receiving data from several handler instances: errors and
 * disconnections are notified via Connect/Disconnect/Error CommEvents,
 * and it is the observer responsibility to take the appropriate
 * actions on them.
 * NOTE: currently, the supported
 */
class ApiHandler[Expected <: Message](private val observer: Observer[CommEvent])
    extends SimpleChannelInboundHandler[Expected] {
    private val log = LoggerFactory.getLogger(classOf[ApiHandler[Expected]])

    override def channelRegistered(ctx: ChannelHandlerContext): Unit = {
        observer.onNext(Connect(ctx))
        super.channelRegistered(ctx)
    }

    override def channelUnregistered(ctx: ChannelHandlerContext): Unit = {
        observer.onNext(Disconnect(ctx))
        super.channelUnregistered(ctx)
    }

    override def channelRead0(ctx: ChannelHandlerContext, msg: Expected):
    Unit = {
        // NOTE: any one wanting to keep the msg must explicitly indicate it,
        // by calling the 'retain' method on it (and explicitly release it with
        // ReferenceCountUtil.release once it is not being used anymore.
        observer.onNext(MsgFactory(ctx, msg))
        ReferenceCountUtil.release(msg)
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable):
    Unit = {
        log.warn("channel exception", cause)
        observer.onNext(Error(ctx, cause))
    }
}

class ApiServerHandler(observer: Observer[CommEvent])
    extends ApiHandler[Commands.Request](observer)
class ApiClientHandler(observer: Observer[CommEvent])
    extends ApiHandler[Commands.Response](observer)


/**
 * Communication events from netty
 */
abstract class CommEvent
case class Connect(ctx: ChannelHandlerContext) extends CommEvent
case class Disconnect(ctx: ChannelHandlerContext) extends CommEvent
case class Error(ctx: ChannelHandlerContext, exc: Throwable) extends CommEvent

/**
 * Message events from netty
 */
abstract class Msg extends CommEvent
case class Request(ctx: ChannelHandlerContext, msg: Commands.Request)
    extends Msg
case class Response(ctx: ChannelHandlerContext, msg: Commands.Response)
    extends Msg

/**
 * Generate the appropriate request or response case class, depending
 * on the type of protobuf
 */
object MsgFactory {
    class InvalidMessageException
        extends RuntimeException("invalid message type")

    def apply(ctx: ChannelHandlerContext, msg: Message): Msg = msg match {
        case req: Commands.Request => Request(ctx, req)
        case rsp: Commands.Response => Response(ctx, rsp)
        case _ => throw new InvalidMessageException
    }
}
