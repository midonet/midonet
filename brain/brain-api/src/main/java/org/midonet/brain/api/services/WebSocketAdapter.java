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

package org.midonet.brain.api.services;

import com.google.protobuf.GeneratedMessage;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Websocket-aware netty adapter
 */
public class WebSocketAdapter<T extends GeneratedMessage>
    extends ChannelInitializer<SocketChannel> {

    // Executor model for the protobuf handler
    private static final int THREADS = 1;
    private final EventExecutorGroup executor =
        new DefaultEventExecutorGroup(THREADS);

    private final SimpleChannelInboundHandler<T> handler;
    private final T prototype;
    private final String urlPath;

    /**
     * Create a websocket adapter (protobuf over websockets)
     * @param handler is the protocol buffer message handler
     * @param prototype is the 'default instance' for the received protobufs
     * @param urlPath is the path section of the websocket url.
     */
    public WebSocketAdapter(SimpleChannelInboundHandler<T> handler,
                            T prototype, String urlPath) {
        this.handler = handler;
        this.prototype = prototype;
        this.urlPath = urlPath;
    }

    @Override
    public void initChannel(SocketChannel ch) {
        final ChannelPipeline pipe = ch.pipeline();
        pipe.addLast(new HttpRequestDecoder());
        pipe.addLast(new HttpObjectAggregator(65566));
        pipe.addLast(new WebSocketServerProtocolHandler(urlPath));
        pipe.addLast(new HttpResponseEncoder());

        pipe.addLast(new WSFrameToBinaryDecoder());
        pipe.addLast(new BinaryToWSFrameEncoder());

        pipe.addLast(new ProtobufVarint32FrameDecoder());
        pipe.addLast(new ProtobufDecoder(prototype));

        pipe.addLast(new ProtobufVarint32LengthFieldPrepender());
        pipe.addLast(new ProtobufEncoder());

        pipe.addLast(executor, handler);
    }
}
