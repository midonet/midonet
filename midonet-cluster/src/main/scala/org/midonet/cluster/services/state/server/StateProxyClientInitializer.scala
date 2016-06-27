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

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder, ProtobufVarint32FrameDecoder, ProtobufVarint32LengthFieldPrepender}

import org.midonet.cluster.rpc.State.ProxyRequest
import org.midonet.cluster.services.state.StateTableManager
import org.midonet.cluster.services.state.server.StateProxyClientInitializer._

object StateProxyClientInitializer {
    final val FrameEncoder = "frameEncoder"
    final val FrameDecoder = "frameDecoder"
    final val MessageEncoder = "messageEncoder"
    final val MessageDecoder = "messageDecoder"
    final val ProtocolHandler = "protocolHandler"
}

/**
  * Handles the initialization of the [[SocketChannel]] for new clients, which
  * consists in adding the following channel handlers to the channel pipeline:
  *
  * - A [[ProtobufDecoder]] that handles inbound byte buffers and decodes them
  *   into a Protocol Buffers protocol message.
  * - A [[ProtobufEncoder]] that handler outbound protocol messages and encodes
  *   them to a byte buffer.
  * - A [[StateProxyProtocolHandler]] that handles the request in the context
  *   of the server internal state machine.
  */
class StateProxyClientInitializer(manager: StateTableManager)
    extends ChannelInitializer[SocketChannel] {

    @throws[Exception]
    override def initChannel(channel: SocketChannel): Unit = {
        channel.pipeline()
            .addLast(FrameDecoder, new ProtobufVarint32FrameDecoder)
            .addLast(MessageDecoder, new ProtobufDecoder(
                ProxyRequest.getDefaultInstance))
            .addLast(FrameEncoder, new ProtobufVarint32LengthFieldPrepender)
            .addLast(MessageEncoder, new ProtobufEncoder)
            .addLast(ProtocolHandler, new StateProxyProtocolHandler(manager))
    }

}
