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

package org.midonet.cluster.services.state.server

import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}

import org.midonet.cluster.rpc.State.{Request, Response}

object StateProxyProtocolHandler {

    private val UnknownMessageError = Response.Error.newBuilder()
        .setCode(Response.Error.Code.UNKNOWN_MESSAGE)
        .setDescription("Unknown message")
        .build()

    private val UnknownProtocolError = Response.Error.newBuilder()
        .setCode(Response.Error.Code.UNKNOWN_PROTOCOL)
        .setDescription("Unknown protocol")
        .build()

}

/**
  * A [[ChannelInboundHandlerAdapter]] instance that handles the State Proxy
  * protocol messages.
  */
class StateProxyProtocolHandler extends ChannelInboundHandlerAdapter {

    @throws[Exception]
    override def channelRead(context: ChannelHandlerContext,
                             message: AnyRef): Unit = {
        message match {
            case request: Request if request.hasRequestId =>
                handleProtocolRequest(context, request)
            case _ =>
                handleUnknownRequest(context)
        }
    }

    @throws[Exception]
    override def exceptionCaught(context: ChannelHandlerContext,
                                 cause: Throwable): Unit = {
        handleUnknownRequest(context)
    }

    /**
      * Handles State Proxy protocol requests.
      */
    private def handleProtocolRequest(context: ChannelHandlerContext,
                                      request: Request): Unit = {
        val response = Response.newBuilder()
            .setRequestId(request.getRequestId)
            .setError(StateProxyProtocolHandler.UnknownMessageError)
            .build()
        context.writeAndFlush(response)
    }

    /**
      * Handles unknown requests by returning an UnknownProtocolError.
      */
    private def handleUnknownRequest(context: ChannelHandlerContext): Unit = {
        val response = Response.newBuilder()
            .setRequestId(-1L)
            .setError(StateProxyProtocolHandler.UnknownProtocolError)
            .build()
        context.writeAndFlush(response)
    }

}
