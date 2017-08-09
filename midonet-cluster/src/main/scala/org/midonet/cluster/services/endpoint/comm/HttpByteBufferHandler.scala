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

import org.slf4j.LoggerFactory

import io.netty.buffer.{ByteBuf, ByteBufInputStream, Unpooled}
import io.netty.channel.{ChannelProgressiveFuture, ChannelProgressiveFutureListener, SimpleChannelInboundHandler}
import io.netty.handler.codec.http._
import io.netty.handler.stream.ChunkedStream
import io.netty.util.CharsetUtil

/**
  * Provider of a ByteBuffer containing the data to be sent as a response
  * on the HTTPByteBufferHandler.
  */
trait HttpByteBufferProvider {
    def getByteBuffer(): ByteBuf
}

/**
  * A netty incoming handler for serving generic binary data based on
  * HTTP GET requests.
  *
  * @param provider object implementing the HTTPByteBufferProvider that provides
  *                 the byte buffer to be sent as a reply to the
  *                 HTTPByteBufferHandler.
  */
class HttpByteBufferHandler(provider: HttpByteBufferProvider)
    extends SimpleChannelInboundHandler[FullHttpRequest] {

    import io.netty.channel.ChannelHandlerContext

    private val log = LoggerFactory.getLogger(classOf[HttpByteBufferHandler])

    override def channelRead0(ctx: ChannelHandlerContext,
                              request: FullHttpRequest) = {
        if (!request.decoderResult().isSuccess) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST)
        } else if (request.method() != HttpMethod.GET) {
            sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED)
        } else {
            val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                                                   HttpResponseStatus.OK)
            val buffer = provider.getByteBuffer()
            response.headers.set(HttpHeaderNames.CACHE_CONTROL,
                                 "no-store, must-revalidate")
            response.headers.set(HttpHeaderNames.CONTENT_LENGTH,
                                 buffer.readableBytes())
            response.headers.set(HttpHeaderNames.CONTENT_TYPE,
                                 "application/octet-stream")
            ctx.write(response)
            sendContents(ctx, buffer)
        }
    }

    private def sendContents(ctx: ChannelHandlerContext,
                             buffer: ByteBuf) = {
        val future: ChannelProgressiveFuture = ctx.writeAndFlush(
            new HttpChunkedInput(
                new ChunkedStream(
                    new ByteBufInputStream(buffer))),
            ctx.newProgressivePromise()).asInstanceOf[ChannelProgressiveFuture]

        if (log.isDebugEnabled) {
            future.addListener(new ChannelProgressiveFutureListener {
                override def operationProgressed
                    (future: ChannelProgressiveFuture,
                     progress: Long,
                     total: Long): Unit = {
                    if (total < 0) {
                        // Total unknown
                        log.debug(future.channel + " Transfer progress: " +
                                  progress)
                    } else {
                        log.debug(future.channel + " Transfer progress: " +
                                  progress + " / " + total)
                    }
                }

                override def operationComplete
                    (future: ChannelProgressiveFuture): Unit = {
                    log.debug(future.channel + " Transfer complete.")
                }
            })
        }
    }

    private def sendError(ctx: ChannelHandlerContext,
                          status: HttpResponseStatus) = {
        val response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            status,
            Unpooled.copiedBuffer("Failure: " + status + "\r\n",
                                  CharsetUtil.UTF_8))

        response.headers.set(HttpHeaderNames.CONTENT_TYPE,
                             "text/plain; charset=UTF-8")

        ctx.writeAndFlush(response)
    }
}
