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

import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger

import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}

import org.slf4j.LoggerFactory

import org.midonet.cluster._
import org.midonet.cluster.rpc.State.ProxyRequest.{Ping, Subscribe, Unsubscribe}
import org.midonet.cluster.rpc.State.ProxyResponse.Pong
import org.midonet.cluster.rpc.State.{ProxyRequest, ProxyResponse}
import org.midonet.cluster.services.state.{StateTableException, StateTableManager}
import org.midonet.cluster.services.state.server.StateProxyProtocolHandler.Log
import org.midonet.cluster.util.UUIDUtil._


object StateProxyProtocolHandler {

    private val Log = Logger(LoggerFactory.getLogger(StateProxyServerLog))

    private val UnknownMessageError = ProxyResponse.Error.newBuilder()
        .setCode(ProxyResponse.Error.Code.UNKNOWN_MESSAGE)
        .setDescription("Unknown message")
        .build()

    private val UnknownProtocolError = ProxyResponse.Error.newBuilder()
        .setCode(ProxyResponse.Error.Code.UNKNOWN_PROTOCOL)
        .setDescription("Unknown protocol")
        .build()

}

/**
  * A [[ChannelInboundHandlerAdapter]] instance that handles the State Proxy
  * protocol messages.
  */
class StateProxyProtocolHandler(manager: StateTableManager)
    extends ChannelInboundHandlerAdapter {

    @throws[Exception]
    override def channelRegistered(context: ChannelHandlerContext): Unit = {
        // Register a new client handler for the new channel.
        Log debug s"Register client=${context.channel().remoteAddress()}"

        try {
            val handler = new ChannelClientHandler(context.channel())
            manager.register(context.channel().remoteAddress(), handler)
        } catch {
            case NonFatal(e) =>
                Log.warn("Unhandled exception for client registration " +
                         s"client=${context.channel().remoteAddress()}", e)
        }
    }

    @throws[Exception]
    override def channelUnregistered(context: ChannelHandlerContext): Unit = {
        // Unregisters a new client handler for the new channel.
        Log debug s"Unregister client=${context.channel().remoteAddress()}"

        try {
            manager.unregister(context.channel().remoteAddress())
        } catch {
            case e: IllegalArgumentException =>
                Log.debug("Client unregistration during shutdown" +
                          s"client=${context.channel().remoteAddress()}")
            case NonFatal(e) =>
                Log.warn("Unhandled exception for client unregistration " +
                         s"client=${context.channel().remoteAddress()}", e)
        }
    }

    @throws[Exception]
    override def channelRead(context: ChannelHandlerContext,
                             message: AnyRef): Unit = {
        message match {
            case request: ProxyRequest if request.hasRequestId =>
                handleProtocolRequest(context, request)
            case _ =>
                handleUnknownProtocol(context)
        }
    }

    @throws[Exception]
    override def exceptionCaught(context: ChannelHandlerContext,
                                 cause: Throwable): Unit = {
        handleUnknownProtocol(context)
    }

    /**
      * Handles State Proxy protocol requests.
      */
    private def handleProtocolRequest(context: ChannelHandlerContext,
                                      request: ProxyRequest): Unit = {
        if (request.hasSubscribe) {
            handleSubscribeRequest(context, request.getRequestId,
                                   request.getSubscribe)
        } else if (request.hasUnsubscribe) {
            handleUnsubscribeRequest(context, request.getRequestId,
                                     request.getUnsubscribe)
        } else if (request.hasPing) {
            handlePingRequest(context, request.getRequestId,
                              request.getPing)
        } else {
            handleUnknownRequest(context, request.getRequestId)
        }
    }

    /**
      * Handles a SUBSCRIBE request.
      */
    private def handleSubscribeRequest(context: ChannelHandlerContext,
                                       requestId: Long,
                                       subscribe: Subscribe): Unit = {
        Log debug s"Request client=${context.channel().remoteAddress()} " +
                  s"reqId=$requestId : SUBSCRIBE " +
                  s"objectClass=${subscribe.getObjectClass} " +
                  s"objectId=${subscribe.getObjectId.asJava} " +
                  s"keyClass=${subscribe.getKeyClass} " +
                  s"valueClass=${subscribe.getValueClass} " +
                  s"tableName=${subscribe.getTableName} " +
                  s"tableArgs=${subscribe.getTableArgumentsList.asByteStringList()}"

        try {
            manager.subscribe(context.channel().remoteAddress(), requestId,
                              subscribe)
        } catch {
            case e: StateTableException =>
                sendStateTableError(context, requestId, e)
            case e: ClientUnregisteredException =>
                sendClientUnregisteredError(context, requestId, e)
            case NonFatal(e) =>
                Log.warn("Unhandled exception for " +
                         s"client=${context.channel().remoteAddress()} " +
                         s"reqId=$requestId", e)
        }
    }

    /**
      * Handles an UNSUBSCRIBE request.
      */
    private def handleUnsubscribeRequest(context: ChannelHandlerContext,
                                         requestId: Long,
                                         unsubscribe: Unsubscribe): Unit = {
        Log debug s"Request client=${context.channel().remoteAddress()} " +
                  s"reqId=$requestId : UNSUBSCRIBE " +
                  s"subId=${unsubscribe.getSubscriptionId}"

        try {
            manager.unsubscribe(context.channel().remoteAddress(), requestId,
                                unsubscribe)
        } catch {
            case e: StateTableException =>
                sendStateTableError(context, requestId, e)
            case e: ClientUnregisteredException =>
                sendClientUnregisteredError(context, requestId, e)
            case NonFatal(e) =>
                Log.warn("Unhandled exception for " +
                         s"client=${context.channel().remoteAddress()} " +
                         s"reqId=$requestId", e)
        }
    }

    /**
      * Handles a PING request.
      */
    private def handlePingRequest(context: ChannelHandlerContext,
                                  requestId: Long, ping: Ping): Unit = {
        Log debug s"Request client=${context.channel().remoteAddress()} " +
                  s"reqId=$requestId : PING"

        val response = ProxyResponse.newBuilder()
            .setRequestId(requestId)
            .setPong(Pong.newBuilder())
            .build()
        context.writeAndFlush(response)
    }

    /**
      * Handles unknown requests by returning an UnknownMessageError.
      */
    private def handleUnknownRequest(context: ChannelHandlerContext,
                                     requestId: Long): Unit = {
        Log info s"Request client=${context.channel().remoteAddress()} " +
                 s"reqId=$requestId : UNKNOWN MESSAGE"

        val response = ProxyResponse.newBuilder()
            .setRequestId(requestId)
            .setError(StateProxyProtocolHandler.UnknownMessageError)
            .build()
        context.writeAndFlush(response)
    }

    /**
      * Handles unknown requests by returning an UnknownProtocolError.
      */
    private def handleUnknownProtocol(context: ChannelHandlerContext): Unit = {
        Log info s"Request client=${context.channel().remoteAddress()} : " +
                 "UNKNOWN PROTOCOL"

        val response = ProxyResponse.newBuilder()
            .setRequestId(-1L)
            .setError(StateProxyProtocolHandler.UnknownProtocolError)
            .build()
        context.writeAndFlush(response)
    }

    private def sendStateTableError(context: ChannelHandlerContext,
                                    requestId: Long,
                                    e: StateTableException): Unit = {
        val response = ProxyResponse.newBuilder()
            .setRequestId(requestId)
            .setError(ProxyResponse.Error.newBuilder()
                          .setCode(e.code)
                          .setDescription(e.getMessage)
                          .build())
            .build()
        context.writeAndFlush(response)
    }

    private def sendClientUnregisteredError(context: ChannelHandlerContext,
                                            requestId: Long,
                                            e: ClientUnregisteredException): Unit = {
        val response = ProxyResponse.newBuilder()
            .setRequestId(requestId)
            .setError(ProxyResponse.Error.newBuilder()
                          .setCode(ProxyResponse.Error.Code.UNKNOWN_CLIENT)
                          .setDescription(e.getMessage)
                          .build())
            .build()
        context.writeAndFlush(response)
    }

}
