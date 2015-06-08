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

package org.midonet.util.netty;

import scala.Option;

import com.google.protobuf.GeneratedMessage;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Bare-bones netty adapter to process protobuf-based requests
 */
public class ProtoBufSocketAdapter<T extends GeneratedMessage>
    extends ChannelInitializer<SocketChannel> {

    // Executor model for the protobuf handle
    private static final int THREADS = 1;
    private final EventExecutorGroup executor =
        new DefaultEventExecutorGroup(THREADS);

    private final SimpleChannelInboundHandler<T> handler;
    private final T prototype;
    private final SslContext sslCtx;

    /**
     * Create a plain adapter pipeline (protobuf-based)
     * @param handler is the protobuf message handler.
     * @param prototype is the 'default instance' for the received protobufs
     */
    public ProtoBufSocketAdapter(SimpleChannelInboundHandler<T> handler,
                                 T prototype) {
        this(handler, prototype, Option.apply((SslContext)null));
    }

    public ProtoBufSocketAdapter(SimpleChannelInboundHandler<T> handler,
                                 T prototype, Option<SslContext> sslCtx) {
        this.handler = handler;
        this.prototype = prototype;
        this.sslCtx = sslCtx.isDefined()? sslCtx.get(): null;
    }

    @Override
    public void initChannel(SocketChannel ch) {
        final ChannelPipeline pipe = ch.pipeline();

        if (sslCtx != null)
            pipe.addLast(sslCtx.newHandler(ch.alloc()));
        pipe.addLast(new ProtobufVarint32FrameDecoder());
        pipe.addLast(new ProtobufDecoder(prototype));
        pipe.addLast(new ProtobufVarint32LengthFieldPrepender());
        pipe.addLast(new ProtobufEncoder());

        // process request
        pipe.addLast(executor, handler);
    }
}
