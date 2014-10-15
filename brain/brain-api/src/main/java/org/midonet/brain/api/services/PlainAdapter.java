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

import rx.subjects.Subject;

import org.midonet.cluster.models.Commons;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Bare-bones netty adapter to process protobuf-based requests
 */
public class PlainAdapter extends ChannelInitializer<SocketChannel> {
    private static final int THREADS = 1;

    private final EventExecutorGroup executor =
        new DefaultEventExecutorGroup(THREADS);

    /** API request flow */
    private final Subject<Request, Request> subject;

    public PlainAdapter(Subject<Request, Request> subject) {
        this.subject = subject;
    }

    @Override
    public void initChannel(SocketChannel ch) {
        final ChannelPipeline pipe = ch.pipeline();

        pipe.addLast(new ProtobufVarint32FrameDecoder());
        pipe.addLast(new ProtobufDecoder(Commons.UUID.getDefaultInstance()));
        pipe.addLast(new ProtobufVarint32LengthFieldPrepender());
        pipe.addLast(new ProtobufEncoder());

        // process request
        pipe.addLast(executor, new ApiServerHandler(subject));
    }
}
