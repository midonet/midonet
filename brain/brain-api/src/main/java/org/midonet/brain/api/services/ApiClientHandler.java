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
import rx.Observer;

import org.midonet.cluster.rpc.Commands;

/**
 * Protocol buffer message handler (simple uuid echo)
 */
public class ApiClientHandler
    extends SimpleChannelInboundHandler<Commands.Response> {
    private static final Logger log =
        LoggerFactory.getLogger(ApiClientHandler.class);

    private final Observer<CommEvent> observer;

    public ApiClientHandler(Observer<CommEvent> observer) {
        this.observer = observer;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        observer.onNext(new Connect(ctx));
        super.channelRegistered(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx)
        throws Exception {
        observer.onNext(new Disconnect(ctx));
        observer.onCompleted();
        super.channelUnregistered(ctx);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Commands.Response msg) {
        // NOTE: msg must be released with ReferenceCountUtil.release(msg)
        // after being processed by the RequestHandler
        observer.onNext(new Response(ctx, msg));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("channel exception", cause);
        observer.onNext(new Error(ctx, cause));
    }
}
