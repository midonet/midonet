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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.subjects.Subject;

import org.midonet.cluster.rpc.Commands;

/**
 * Protocol buffer message handler (simple uuid echo)
 */
public class ApiServerHandler
    extends SimpleChannelInboundHandler<Commands.Request> {
    private static final Logger log =
        LoggerFactory.getLogger(ApiServerHandler.class);

    private final Subject<CommEvent, CommEvent> subject;

    public ApiServerHandler(Subject<CommEvent, CommEvent> subject) {
        this.subject = subject;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        subject.onNext(new Connect(ctx));
        super.channelRegistered(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx)
        throws Exception {
        subject.onNext(new Disconnect(ctx));
        super.channelUnregistered(ctx);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Commands.Request msg) {
        // NOTE: msg must be released with ReferenceCountUtil.release(msg)
        // after being processed by the RequestHandler
        subject.onNext(new Request(ctx, msg));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("channel exception", cause);
        subject.onNext(new Error(ctx, cause));
    }
}
