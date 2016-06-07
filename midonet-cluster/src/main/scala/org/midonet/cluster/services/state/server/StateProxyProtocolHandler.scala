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

import org.midonet.cluster.rpc.State.Message

object StateProxyProtocolHandler {

    private val UnknownMessageError = Message.Error.newBuilder()
        .setCode(Message.Error.Code.UNKNWON_MESSAGE)
        .setDescription("Unknown message")
        .build()

    private val UnknownProtocolError = Message.Error.newBuilder()
        .setCode(Message.Error.Code.UNKNWON_PROTOCOL)
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
            case request: Message =>
                handleProtocolRequest(context, request)
            case _ =>
                handleUnknownRequest(context, message)
        }
    }

    private def handleProtocolRequest(context: ChannelHandlerContext,
                                      request: Message): Unit = {
        val response = Message.newBuilder()
            .setRequestId(request.getRequestId)
            .setError(StateProxyProtocolHandler.UnknownMessageError)
            .build()
        context.writeAndFlush(response)
    }

    private def handleUnknownRequest(context: ChannelHandlerContext,
                                     request: AnyRef): Unit = {
        val response = Message.newBuilder()
            .setRequestId(-1L)
            .setError(StateProxyProtocolHandler.UnknownProtocolError)
            .build()
        context.writeAndFlush(response)
    }

}
