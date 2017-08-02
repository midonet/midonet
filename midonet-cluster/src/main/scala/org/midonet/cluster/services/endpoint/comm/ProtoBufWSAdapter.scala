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

import com.google.protobuf.Message

import org.midonet.util.netty.{BinaryToWSFrameEncoder, WSFrameToBinaryDecoder}

import io.netty.channel.socket.SocketChannel
import io.netty.channel.{ChannelInitializer, ChannelPipeline}
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.codec.http.{HttpRequestDecoder, HttpServerCodec}
import io.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder, ProtobufVarint32FrameDecoder, ProtobufVarint32LengthFieldPrepender}
import io.netty.handler.ssl.SslContext

/**
  * Netty channel initializer for ProtoBuf communication over WebSockets.
  *
  * @param wsPath HTTP path on which to listen for WS requests.
  * @param reqPrototype Prototype of received protobuf messages.
  * @param sslCtx Optional ssl context to be used if there's no http adapter in
  *               pipeline.
  */
class ProtoBufWSAdapter(wsPath: String, reqPrototype: Message,
                        sslCtx: => Option[SslContext] = None)
    extends ChannelInitializer[SocketChannel] {
    override def initChannel(ch: SocketChannel) = {
        val pipe: ChannelPipeline = ch.pipeline()

        val httpRequestDecoder = Option(pipe.get(classOf[HttpRequestDecoder]))
        val httpCodec = Option(pipe.get(classOf[HttpServerCodec]))

        // Initialize http stack if not present
        if (httpRequestDecoder.isEmpty && httpCodec.isEmpty) {
            val httpAdapter = new HTTPAdapter(sslCtx)
            httpAdapter.initChannel(ch)
        }

        pipe.addLast(new WebSocketServerProtocolHandler(wsPath))

        pipe.addLast(new WSFrameToBinaryDecoder())
        pipe.addLast(new BinaryToWSFrameEncoder())

        pipe.addLast(new ProtobufVarint32FrameDecoder())
        pipe.addLast(new ProtobufDecoder(reqPrototype))

        pipe.addLast(new ProtobufVarint32LengthFieldPrepender())
        pipe.addLast(new ProtobufEncoder())
    }
}


