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
import java.nio.file.FileSystemException
import java.util.UUID

import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting

import org.midonet.cluster.flowstate.FlowStateTransfer.StateRequest
import org.midonet.cluster.flowstate.FlowStateTransfer.StateResponse.Error
import org.midonet.cluster.models.Commons.IPAddress
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.services.flowstate.FlowStateService._
import org.midonet.services.flowstate.stream._
import org.midonet.services.flowstate.transfer.StateTransferProtocolBuilder._
import org.midonet.services.flowstate.transfer.StateTransferProtocolParser._
import org.midonet.services.flowstate.transfer.client.FlowStateRemoteClient
import org.midonet.services.flowstate.transfer.internal.{InvalidStateRequest, StateRequestInternal, StateRequestRaw, StateRequestRemote}

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled._
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._

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
class FlowStateReadHandler(context: Context)
    extends SimpleChannelInboundHandler[ByteBuf] {

    private val tcpClient = new FlowStateRemoteClient(context.config)

    private def eof = copyInt(0)

    @VisibleForTesting
    protected def getByteBufferBlockReader(portId: UUID) =
        ByteBufferBlockReader(context, portId)

    @VisibleForTesting
    protected def getFlowStateReader(portId: UUID) =
        FlowStateReader(context, portId)

    @VisibleForTesting
    @throws[FileSystemException]
    protected def getByteBufferBlockWriter(portId: UUID) =
        context.ioManager.blockWriter(portId)

    override def channelRead0(context: ChannelHandlerContext,
                              msg: ByteBuf): Unit = {

        parseSegment(msg) match {
            case StateRequestInternal(portId) =>
                Log debug s"Flow state internal request for port: ${fromProto(portId)}"
                respondInternal(context, portId)
            case StateRequestRemote(portId, address) =>
                Log debug s"Flow state remote [${address.getAddress}] request " +
                          s"for port: ${fromProto(portId)}"
                respondRemote(context, portId, address)
            case StateRequestRaw(portId) =>
                Log debug s"Flow state raw request for port: ${fromProto(portId)}"
                respondRaw(context, portId)
            case InvalidStateRequest(e) =>
                Log warn s"Invalid flow state request: ${e.getMessage}"
                val error = buildError(Error.Code.BAD_REQUEST, e).toByteArray

                writeAndFlushWithHeader(context, error)
        }

        context.close()
    }

    private def respondRaw(ctx: ChannelHandlerContext, portId: UUID): Unit = {
        try {
            val ack = buildAck(portId).toByteArray
            writeAndFlushWithHeader(ctx, ack)

            val in = getByteBufferBlockReader(portId)
            val headerBuff = new Array[Byte](FlowStateBlock.headerSize)
            val blockBuff = new Array[Byte](context.config.blockSize)

            in.read(headerBuff)
            var header = FlowStateBlock(ByteBuffer.wrap(headerBuff))
            var next = in.read(blockBuff, 0, header.blockLength)

            while (next > 0) {
                ctx.write(copyInt(next + FlowStateBlock.headerSize))
                ctx.write(copiedBuffer(headerBuff))
                ctx.writeAndFlush(copiedBuffer(blockBuff))

                in.read(headerBuff)
                header = FlowStateBlock(ByteBuffer.wrap(headerBuff))
                next = in.read(blockBuff, 0, header.blockLength)
            }

            ctx.writeAndFlush(eof)

            context.ioManager.remove(portId)
        } catch {
            case NonFatal(e) => handleStorageError(ctx, portId, e)
        }
    }

    private def respondInternal(ctx: ChannelHandlerContext, portId: UUID): Unit = {
        try {
            val ack = buildAck(portId).toByteArray
            writeAndFlushWithHeader(ctx, ack)

            readFromLocalState(ctx, portId)
        } catch {
            case NonFatal(e) => handleStorageError(ctx, portId, e)
        }
    }

    private def respondRemote(ctx: ChannelHandlerContext,
                              portId: UUID, address: IPAddress): Unit = {
        try {
            // Request and save all flow state locally
            val out = getByteBufferBlockWriter(portId)
            tcpClient.rawPipelinedFlowStateFrom(address.getAddress, portId, out)

            val ack = buildAck(portId).toByteArray
            writeAndFlushWithHeader(ctx, ack)

            readFromLocalState(ctx, portId)
        } catch {
            case NonFatal(e) => handleStorageError(ctx, portId, e)
        }
    }

    private def readFromLocalState(ctx: ChannelHandlerContext,
                                   portId: UUID): Unit = {
        // Expire blocks before actually start reading from it. Expiration
        // is done lazily to avoid excessive delays on the boot sequence.
        try {
            context.ioManager.blockWriter(portId).invalidateBlocks(excludeBlocks = 0)

            // Blocks are up to date, read and send it back to the agent.
            val in = getFlowStateReader(portId)

            var next = in.read()
            while (next.isDefined) {
                val sbeRaw = next.get.flowStateBuffer.array()
                writeAndFlushWithHeader(ctx, sbeRaw)
                next = in.read()
            }

            ctx.writeAndFlush(eof)
        } catch {
            case NonFatal(e) => handleStorageError(ctx, portId, e)
        }
    }

    private def handleStorageError(ctx: ChannelHandlerContext,
                                   portId: UUID, e: Throwable): Unit = {
        Log warn s"Error handling flow state request for port $portId: " +
                 s"${e.getMessage}"
        val error = buildError(Error.Code.STORAGE_ERROR, e)

        writeAndFlushWithHeader(ctx, error.toByteArray)
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
    private def writeAndFlushWithHeader(ctx: ChannelHandlerContext,
                                        data: Array[Byte]): Unit = {
        val sizeHeader = copyInt(data.size)
        ctx.write(sizeHeader)
        ctx.writeAndFlush(copiedBuffer(data))
    }

}