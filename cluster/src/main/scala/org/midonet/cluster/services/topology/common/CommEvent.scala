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

import org.midonet.cluster.rpc.Commands

import io.netty.channel.ChannelHandlerContext

/**
 * Communication events from Netty
 */
abstract class CommEvent

case class Connect(ctx: ChannelHandlerContext) extends CommEvent
case class Disconnect(ctx: ChannelHandlerContext) extends CommEvent
case class Error(ctx: ChannelHandlerContext, exc: Throwable) extends CommEvent

case class Request(ctx: ChannelHandlerContext, req: Commands.Request)
    extends CommEvent
case class Response(ctx: ChannelHandlerContext, rsp: Commands.Response)
    extends CommEvent

/**
 * Used to signal the protocol that the underlying connection has been
 * interrupted.
 */
case object Interruption
