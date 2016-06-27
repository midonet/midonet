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
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled._
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._
import org.midonet.cluster.flowstate.FlowStateTransfer.StateRequest
import org.midonet.cluster.flowstate.FlowStateTransfer.StateResponse.Error
import org.midonet.midolman.config.FlowStateConfig
import org.midonet.midolman.state.transfer.FlowStateRequestClient
import org.midonet.services.flowstate.FlowStateService._
import org.midonet.services.flowstate.stream._
import org.midonet.services.flowstate.transfer.StateTransferProtocolBuilder._
import org.midonet.services.flowstate.transfer.StateTransferProtocolParser._
import org.midonet.services.flowstate.transfer.internal.{InvalidStateRequest, StateRequestInternal, StateRequestRaw, StateRequestRemote}

import scala.util.control.NonFatal

/** Handler used to receive flow state read requests from agents and forward
  * back the requested flow state reusing the same socket.
  *
  * The read requests can come from the Agent running in the same host, or from
  * a remote flow state minion running on a different machine, requesting the
  * flow state to transfer it to its own Agent.
  *
  * In the first case, the flow state will be read from local storage directly
  * and sent back. In the case of a transfer from a different agent, the raw
  * flow state data will be sent, then saved locally and decompressed for the
  * client requesting it.
  */
@Sharable
class FlowStateReadHandler(config: FlowStateConfig)
    extends SimpleChannelInboundHandler[ByteBuf] {

    private def EOF = copyInt(0)
    private val tcpClient = new FlowStateRequestClient(config.tcpTimeout)
    private var ctx: ChannelHandlerContext = _

    @VisibleForTesting
    protected[flowstate] def getTcpClient = tcpClient

    @VisibleForTesting
    protected def getByteBufferBlockReader(portId: UUID) =
        ByteBufferBlockReader(config, portId)

    @VisibleForTesting
    protected def getByteBufferBlockWriter(portId: UUID) =
        ByteBufferBlockWriter(config, portId)

    @VisibleForTesting
    protected def getFlowStateReader(portId: UUID) =
        FlowStateReader(config, portId)

    override def channelRead0(context: ChannelHandlerContext,
                              msg: ByteBuf): Unit = {
        Log debug "Flow state read request received"
        ctx = context

        parseSegment(msg) match {
            case StateRequestInternal(portId) =>
                Log debug s"Flow state internal request for port: $portId"
                respondInternal(portId)
            case StateRequestRemote(portId, host) =>
                Log debug s"Flow state remote[$host] request for port: $portId"
                respondRemote(portId, host)
            case StateRequestRaw(portId) =>
                Log debug s"Flow state raw request for port: $portId"
                respondRaw(portId)
            case InvalidStateRequest(e) =>
                Log warn s"Invalid flow state request: $e"
                val error = buildError(Error.Code.BAD_REQUEST, e).toByteArray

                writeAndFlushWithHeader(error)
        }

        ctx.close()
    }

    private def respondRaw(portId: UUID): Unit = {
        try {
            val ack = buildAckRaw(portId).toByteArray
            writeAndFlushWithHeader(ack)

            val in = getByteBufferBlockReader(portId)
            val buffer = new Array[Byte](config.blockSize)
            var next = in.read(buffer)

            while (next > 0) {
                ctx.write(copyInt(next))
                ctx.writeAndFlush(copiedBuffer(buffer))
                next = in.read(buffer)
            }

            ctx.writeAndFlush(EOF)
        } catch {
            case NonFatal(e) => handleStorageError(portId, e)
        }
    }

    private def respondInternal(portId: UUID): Unit = {
        try {
            val ack = buildAckInternal(portId).toByteArray
            writeAndFlushWithHeader(ack)

            readFromLocalState(portId)
        } catch {
            case NonFatal(e) => handleStorageError(portId, e)
        }
    }

    private def respondRemote(portId: UUID, host: String): Unit = {
        try {
            // Request and save all flow state locally
            val out = getByteBufferBlockWriter(portId)
            getTcpClient.rawPipelinedFlowStateFrom(host, config.tcpPort, portId, out)

            val ack = buildAckRemote(portId).toByteArray
            writeAndFlushWithHeader(ack)

            readFromLocalState(portId)
        } catch {
            case NonFatal(e) => handleStorageError(portId, e)
        }
    }

    private def readFromLocalState(portId: UUID): Unit = {
        val in = getFlowStateReader(portId)

        var next = in.read()
        while (next.isDefined) {
            val sbeRaw = next.get.flowStateBuffer.array()
            writeAndFlushWithHeader(sbeRaw)
            next = in.read()
        }

        ctx.writeAndFlush(EOF)
    }

    private def handleStorageError(portId: UUID, e: Throwable): Unit = {
        Log warn(s"Error handling flow state request for port: $portId", e)
        val error = buildError(Error.Code.STORAGE_ERROR, e)

        writeAndFlushWithHeader(error.toByteArray)
    }

    private def parseSegment(msg: ByteBuf) = {
        try {
            val data = new Array[Byte](msg.readableBytes)
            msg.getBytes(0, data)
            val request = StateRequest.parseFrom(data)
            parseStateRequest(request)
        } catch {
            case NonFatal(e) => InvalidStateRequest(e)
        }
    }

    // Helper to send an array through the stream prepending its size
    private def writeAndFlushWithHeader(data: Array[Byte]): Unit = {
        val sizeHeader = copyInt(data.size)
        ctx.write(sizeHeader)
        ctx.writeAndFlush(copiedBuffer(data))
    }

}