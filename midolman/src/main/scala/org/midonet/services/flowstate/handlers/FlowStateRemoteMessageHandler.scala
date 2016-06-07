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

import java.util.UUID

import com.google.common.annotations.VisibleForTesting
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._
import org.midonet.cluster.flowstate.FlowStateTransfer.StateForPortRequest
import org.midonet.cluster.flowstate.FlowStateTransfer.StateForPortResponse.Error
import org.midonet.services.flowstate.FlowStateService._
import org.midonet.services.flowstate.transfer.StateTransferProtocolBuilder._
import org.midonet.services.flowstate.transfer.StateTransferProtocolParser
import org.midonet.services.flowstate.transfer.internal.{InvalidStateTransferRequest, StateForPort}
import org.midonet.services.flowstate.{FlowStateBuffer, FlowStateStorageProvider}

import scala.util.control.NonFatal

/** Handler used to receive flow state requests from agents and forward back
  * the requested flow state reusing the same socket */
@Sharable
class FlowStateRemoteMessageHandler(flowStateStorageProvider: FlowStateStorageProvider)
    extends SimpleChannelInboundHandler[ByteBuf] {

    @VisibleForTesting
    protected[flowstate] def getLocalStorageProvider = flowStateStorageProvider

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: ByteBuf): Unit = {
        Log debug "Flow state request received"

        parseSegment(msg) match {
            case StateForPort(portId) =>
                Log debug s"Flow state request for port: $portId"
                respond(ctx, portId)
            case InvalidStateTransferRequest(e) =>
                Log warn s"Invalid flow state request: $e"
                val error = buildError(Error.Code.BAD_REQUEST, e)

                ctx.writeAndFlush(Unpooled.wrappedBuffer(error.toByteArray))
        }
    }

    private def respond(ctx: ChannelHandlerContext, portId: UUID): Unit = {

        try {
            val FlowStateBuffer(in, out) = getLocalStorageProvider.get(portId)
            out.close()
            val data = in.read()
            val ack = buildAck(portId, data.array())

            ctx.writeAndFlush(Unpooled.wrappedBuffer(ack.toByteArray))

            Log debug s"Flow state ack sent to port: $portId"
        } catch {
            case NonFatal(e) =>
                Log warn (s"Error handling flow state request for port: $portId", e)
                val error = buildError(Error.Code.STORAGE_ERROR, e)

                ctx.writeAndFlush(Unpooled.wrappedBuffer(error.toByteArray))
        }
    }

    private def parseSegment(msg: ByteBuf) = {
        try {
            val request = StateForPortRequest.parseFrom(msg.array())
            StateTransferProtocolParser.parseTransferRequest(request)
        } catch {
            case NonFatal(e) => InvalidStateTransferRequest(e)
        }
    }
}