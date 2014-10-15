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

import com.google.common.util.concurrent.AbstractService;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain netty-based server
 */
public class ClientFrontEnd extends AbstractService {
    private static final Logger log =
        LoggerFactory.getLogger(ClientFrontEnd.class);

    private final String host;
    private final int port;
    private final EventLoopGroup wrkr = new NioEventLoopGroup();
    private ChannelFuture sock;

    private final ChannelInboundHandlerAdapter adapter;

    public ClientFrontEnd(ChannelInboundHandlerAdapter adapter, String host, int port) {
        this.adapter = adapter;
        this.host = host;
        this.port = port;
    }

    @Override
    protected void doStart() {
        log.info("Starting client");
        try {
            Bootstrap boot = new Bootstrap();
            boot.group(wrkr)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(adapter);
            sock = boot.connect(host, port).sync();
            log.info("Client started");
            notifyStarted();
        } catch (InterruptedException e) {
            log.info("Client start interrupted");
            Thread.currentThread().interrupt();
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        log.info("Stopping client");
        wrkr.shutdownGracefully();
        try {
            sock.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.info("Client stop interrupted");
            Thread.currentThread().interrupt();
        }
        log.info("Client stopped");
        notifyStopped();
    }
}
