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
package org.midonet.services.flowstate.handlers

import java.nio.ByteBuffer
import java.util.UUID

import com.google.common.annotations.VisibleForTesting
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._
import org.midonet.services.flowstate.FlowStateService._
import org.midonet.services.flowstate.FlowStateStorageProvider

import scala.util.control.NonFatal

trait FlowStateRequest
case class ValidStateRequest(portId: UUID) extends FlowStateRequest
case class InvalidStateRequest(e: Throwable) extends FlowStateRequest

/** Handler used to receive flow state requests from agents and forward back
  * the requested flow state reusing the same socket */
@Sharable
class FlowStateRemoteMessageHandler(flowStateStorageProvider: FlowStateStorageProvider)
    extends SimpleChannelInboundHandler[ByteBuf] {

    private val UUIDLength = 36

    @VisibleForTesting
    protected[flowstate] def getLocalStorageProvider = flowStateStorageProvider

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: ByteBuf): Unit = {
        Log debug "Flow state request received"

        parseSegment(msg) match {
            case ValidStateRequest(portId) =>
                respond(ctx, portId)
            case InvalidStateRequest(e) =>
                Log warn s"Invalid flow state request, ignoring: $e"
        }
    }

    private def respond(ctx: ChannelHandlerContext, portId: UUID): Unit = {
        val data: ByteBuffer = getLocalStorageProvider.get(portId).in.read()
        val size = ByteBuffer.allocate(Integer.BYTES).putInt(data.capacity)

        ctx.write(Unpooled.wrappedBuffer(size.array()))
        ctx.writeAndFlush(Unpooled.wrappedBuffer(data.array()))

        Log debug s"Flow state response sent to port: $portId"
    }

    private def parseSegment(msg: ByteBuf): FlowStateRequest = {
        try {
            val bb = ByteBuffer.allocate(UUIDLength)
            msg.getBytes(0, bb)
            val requestedPort = new String(bb.array())
            val portId = UUID.fromString(requestedPort)
            Log debug s"Flow state request for port: $portId"
            ValidStateRequest(portId)
        } catch {
            case NonFatal(e) => InvalidStateRequest(e)
        }
    }
}