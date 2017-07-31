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

import java.net.URLDecoder

import org.slf4j.LoggerFactory

import org.midonet.cluster.services.endpoint.registration.EndpointUserRegistrar

import io.netty.buffer.Unpooled
import io.netty.channel.{ChannelFutureListener, SimpleChannelInboundHandler}
import io.netty.handler.codec.http.{DefaultFullHttpResponse, HttpRequest, HttpResponseStatus}

/**
  * A netty incoming handler that performs further initialization of an endpoint
  * channel based on the path of the URL of the first HTTP request received in
  * that channel.
  *
  * @param userRegistrar Registrar containing users that have registered to a
  *                      particular HTTP path.
  */
class HTTPPathBasedInitHandler(userRegistrar: EndpointUserRegistrar)
    extends SimpleChannelInboundHandler[HttpRequest](false) {

    import io.netty.channel.ChannelHandlerContext

    private val log = LoggerFactory.getLogger(classOf[HTTPPathBasedInitHandler])

    /**
      * Find and execute custom endpoint channel initialization based on the URL
      * path mapping passed in the constructor applied to the URL of this
      * HttpRequest.
      *
      * Note that this will only be called once by channel whenever we receive
      * the first HttpRequest. After that, the initialization will have been
      * done (and we'll have removed this initialization handler) so everything
      * should be handled correctly.
      *
      * @param ctx Context for this channel handler.
      * @param msg First HttpRequest message seen in this channel.
      */
    override def channelRead0(ctx: ChannelHandlerContext, msg: HttpRequest) = {
        userRegistrar.findUserForPath(
            URLDecoder.decode(msg.uri, "UTF-8")) match {
            // If we have a matching initializer, modify the pipeline
            case Some((matchedPath, user)) =>
                user.initEndpointChannel(matchedPath, ctx.channel)
                // Let new handlers know this channel is active
                ctx.fireChannelActive()
                // Remove ourselves since initialization is done
                ctx.pipeline().remove(this)
                // Add an exception catcher at the end to automatically close
                // channels with unhandled exceptions
                ctx.pipeline().addLast(new CloseOnExceptionHandler)
                ctx.fireChannelRead(msg)
            // Otherwise answer with a 404 and close the channel
            case _ =>
                log.warn(s"No initializer found for uri: ${msg.uri}")
                // Sending a zero-length chunk, according to RFC-2616 Sec, 8.1.2
                ctx.writeAndFlush(
                    new DefaultFullHttpResponse(msg.protocolVersion,
                                                HttpResponseStatus.NOT_FOUND,
                                                Unpooled.buffer(0,0)))
                        .addListener(ChannelFutureListener.CLOSE)
        }
    }
}
