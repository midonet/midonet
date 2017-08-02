/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.services.endpoint.comm

import java.util.concurrent.TimeUnit

import org.slf4j.LoggerFactory

import io.netty.channel._
import io.netty.handler.codec.http._
import io.netty.handler.timeout.{IdleStateEvent, IdleStateHandler}
import io.netty.util.AttributeKey

/**
  * A Netty handler to implement keep-alive logic on all the petitions.
  *
  * Additionally, controls the idle state of the connections and automatically
  * closes open connections that reached a timeout
  *
  */
class KeepAliveHandler(idleTimeout: Int)
            extends IdleStateHandler(0, 0, idleTimeout, TimeUnit.MILLISECONDS) {
    import KeepAliveHandler.{Log, RequestAttrKey}

    Log.trace("Starting keepAliveHandler with timeout = {}ms", idleTimeout)

    override def write(ctx: ChannelHandlerContext,
                       msg: scala.Any,
                       promise: ChannelPromise): Unit = {
        val request = ctx.attr(RequestAttrKey).get()
        msg match {
            case response: HttpResponse =>
                Log.trace("Found HttpResponse")
                if (!HttpUtil.isTransferEncodingChunked(response)){
                    if(HttpUtil.isKeepAlive(request)) {
                        Log.trace("Is Keep-Alive")
                        if(response.isInstanceOf[FullHttpResponse]) {
                            response.headers.set(
                                HttpHeaderNames.CONTENT_LENGTH,
                                response.asInstanceOf[FullHttpResponse]
                                    .content().readableBytes())
                        }
                        // Add keep alive header as per:
                        // http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
                        response.headers.set(HttpHeaderNames.CONNECTION,
                                             HttpHeaderValues.KEEP_ALIVE)
                    } else {
                        Log.trace("Keep alive NOT implemented ")
                        promise.addListener(ChannelFutureListener.CLOSE)
                    }
                } else {
                    Log.trace("Chunked HttpResponse")
                    response.headers.remove(HttpHeaderNames.CONTENT_LENGTH)
                    if (!HttpUtil.isKeepAlive(request)) {
                        response.headers().remove(HttpHeaderNames.CONNECTION)
                        Log.trace("Keep NOT implemented")
                    } else {
                        Log.trace("keep alive IMPLIED for this connection")
                    }
                }
            case lastChunk: LastHttpContent =>
                Log.trace("Found Latest HTTP Chunk")
                if (!HttpUtil.isKeepAlive(request)) {
                    Log.trace("Last chunk on NOT keepalive connection")
                    promise.addListener(ChannelFutureListener.CLOSE)
                }
            case chunk: HttpContent =>
                Log.trace("Found HTTP Chunk")
            case d => Log.trace("Found: {}", d.getClass)
        }

        super.write(ctx, msg, promise)
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any) = {
        if (msg.isInstanceOf[HttpRequest]) {
            val request = msg.asInstanceOf[HttpRequest]
            ctx.attr(RequestAttrKey).set(request)
        }
        super.channelRead(ctx, msg)
    }

    override def channelIdle(ctx: ChannelHandlerContext,
                             evt: IdleStateEvent): Unit = {
        Log.trace("Channel got idle")
        ctx.channel().close()
    }
}

object KeepAliveHandler {
    private final val RequestAttrKeyName = "request"
    protected[comm] final val RequestAttrKey : AttributeKey[HttpRequest] =
        AttributeKey.valueOf(classOf[HttpRequest], RequestAttrKeyName)

    private final val Log = LoggerFactory.getLogger(classOf[KeepAliveHandler])
}

