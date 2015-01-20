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

package org.midonet.util.netty

import java.net.URI

import com.google.protobuf.GeneratedMessage
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.codec.http._
import io.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder, ProtobufVarint32FrameDecoder, ProtobufVarint32LengthFieldPrepender}
import io.netty.util.concurrent.{DefaultEventExecutorGroup, EventExecutorGroup}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{Future, Promise}

/**
 * Websocket-aware netty adapter (protobuf over websockets)
 * @param handler is the protocol buffer message handler
 * @param prototype is the 'default instance' for the received protobufs
 * @param uri is the websocket url.
 */
abstract class ProtoBufWebSocketAdapter[T <: GeneratedMessage](
    val handler: SimpleChannelInboundHandler[T], val prototype: T, val uri: URI)
    extends ChannelInitializer[SocketChannel] {

    import ProtoBufWebSocketAdapter._

    // Executor model for the protobuf handler
    private final val THREADS: Int = 1
    private val executor: EventExecutorGroup =
        new DefaultEventExecutorGroup(THREADS)

    protected final val handshaked: TrieMap[Channel, Promise[Channel]] =
        new TrieMap()

    /**
     * Get a future to check if the channel has completed the
     * websocket handshake
     * @param ch is the channel to check
     * @return a future containing the channel context, on success
     */
    final def checkHandshake(ch: Channel): Future[Channel] = {
        handshaked.putIfAbsent(ch, Promise[Channel]())
        handshaked.get(ch).map({_.future}).get
    }

    /**
     * Create a new Websocket protocol handler to add to channel pipeline
     */
    protected def generateWSHandler(): WSHandler

    override def initChannel(ch: SocketChannel) = {
        val pipe: ChannelPipeline = ch.pipeline()

        generateWSHandler() match {
            case WSClientHandler(clientProtocolHandler) =>
                pipe.addLast(new HttpClientCodec())
                pipe.addLast(new HttpObjectAggregator(65536))
                pipe.addLast(clientProtocolHandler)
            case WSServerHandler(serverProtocolHandler) =>
                pipe.addLast(new HttpRequestDecoder())
                pipe.addLast(new HttpObjectAggregator(65536))
                pipe.addLast(serverProtocolHandler)
                pipe.addLast(new HttpResponseEncoder())
        }

        pipe.addLast(new WSFrameToBinaryDecoder())
        pipe.addLast(new BinaryToWSFrameEncoder())

        pipe.addLast(new ProtobufVarint32FrameDecoder())
        pipe.addLast(new ProtobufDecoder(prototype))

        pipe.addLast(new ProtobufVarint32LengthFieldPrepender())
        pipe.addLast(new ProtobufEncoder())

        pipe.addLast(executor, handler)
    }

}

protected object ProtoBufWebSocketAdapter {
    abstract class WSHandler
    case class WSClientHandler(client: WebSocketClientProtocolHandler)
        extends WSHandler
    case class WSServerHandler(server: WebSocketServerProtocolHandler)
        extends WSHandler

    trait HandshakeHandler {
        final def registerHandshake(
            hs: TrieMap[Channel, Promise[Channel]], ch: Channel) =
            hs.putIfAbsent(ch, Promise[Channel]())
        final def unregisterHandshake(
            hs: TrieMap[Channel, Promise[Channel]], ch: Channel) =
            hs.remove(ch).foreach({_.tryFailure(new Exception(
                "channel closed before websocket handshake completion"))})
        final def triggerHandshakeCompleted(
             hs: TrieMap[Channel, Promise[Channel]], ch: Channel) =
            hs.get(ch).foreach({_.trySuccess(ch)})
    }
}

class ProtoBufWebSocketServerAdapter[T <: GeneratedMessage](
    handler: SimpleChannelInboundHandler[T], prototype: T, uri: URI)
    extends ProtoBufWebSocketAdapter(handler, prototype, uri) {
    def this(handler: SimpleChannelInboundHandler[T], prototype: T,
             wsPath: String) =
        this(handler, prototype, URI.create(wsPath))
    import ProtoBufWebSocketAdapter._

    private class Handler extends WebSocketServerProtocolHandler(uri.toString)
                                  with HandshakeHandler {
        import WebSocketServerProtocolHandler.ServerHandshakeStateEvent
        override def channelRegistered(ctx: ChannelHandlerContext) = {
            registerHandshake(handshaked, ctx.channel())
            super.channelRegistered(ctx)
        }
        override def channelUnregistered(ctx: ChannelHandlerContext) = {
            unregisterHandshake(handshaked, ctx.channel())
            super.channelUnregistered(ctx)
        }
        override def userEventTriggered(ctx: ChannelHandlerContext, ev: Any) = {
            if (ev == ServerHandshakeStateEvent.HANDSHAKE_COMPLETE)
                triggerHandshakeCompleted(handshaked, ctx.channel())
            super.userEventTriggered(ctx, ev)
        }
    }

    def generateWSHandler(): WSHandler = WSServerHandler(new Handler)
}

class ProtoBufWebSocketClientAdapter[T <: GeneratedMessage](
    handler: SimpleChannelInboundHandler[T], prototype: T, uri: URI)
    extends ProtoBufWebSocketAdapter(handler, prototype, uri) {
    def this(handler: SimpleChannelInboundHandler[T], prototype: T,
        uriString: String) =
        this(handler, prototype, URI.create(uriString))
    import ProtoBufWebSocketAdapter._

    private class Handler(handshaker: WebSocketClientHandshaker)
        extends WebSocketClientProtocolHandler(handshaker, true)
                with HandshakeHandler {

        import WebSocketClientProtocolHandler.ClientHandshakeStateEvent
        override def channelRegistered(ctx: ChannelHandlerContext) = {
            registerHandshake(handshaked, ctx.channel())
            super.channelRegistered(ctx)
        }
        override def channelUnregistered(ctx: ChannelHandlerContext) = {
            unregisterHandshake(handshaked, ctx.channel())
            super.channelUnregistered(ctx)
        }
        override def userEventTriggered(ctx: ChannelHandlerContext, ev: Any) = {
            if (ev == ClientHandshakeStateEvent.HANDSHAKE_COMPLETE)
                triggerHandshakeCompleted(handshaked, ctx.channel())
            super.userEventTriggered(ctx, ev)
        }
    }

    def generateWSHandler(): WSHandler = {
        val handshaker = WebSocketClientHandshakerFactory.newHandshaker(
            uri, WebSocketVersion.V13, null, false, null)
        WSClientHandler(new Handler(handshaker))
    }
}

