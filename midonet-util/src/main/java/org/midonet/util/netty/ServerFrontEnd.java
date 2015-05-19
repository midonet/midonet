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

import com.google.common.util.concurrent.AbstractService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Plain netty-based server
 */
public class ServerFrontEnd extends AbstractService {
    private static final Logger log =
        LoggerFactory.getLogger(ServerFrontEnd.class);

    private final boolean datagram;
    private final int port;
    private final EventLoopGroup boss;
    private final EventLoopGroup wrkr = new NioEventLoopGroup();
    private ChannelFuture sock;

    private final ChannelInboundHandlerAdapter adapter;

    public static ServerFrontEnd udp(ChannelInboundHandlerAdapter adapter, int port) {
        return new ServerFrontEnd(adapter, port, true);
    }

    public static ServerFrontEnd tcp(ChannelInboundHandlerAdapter adapter, int port) {
        return new ServerFrontEnd(adapter, port, false);
    }

    private ServerFrontEnd(ChannelInboundHandlerAdapter adapter, int port, boolean datagram) {
        this.adapter = adapter;
        this.port = port;
        this.datagram = datagram;
        if(datagram) {
            boss = null;
        } else {
            boss = new NioEventLoopGroup();
        }
    }

    @Override
    protected void doStart() {

        try {
            if(datagram) {
                log.info("Starting Netty UDP server on port {}", port);
                Bootstrap boot = new Bootstrap();
                boot.group(wrkr)
                    .channel(NioDatagramChannel.class)
                    .handler(adapter);
                sock = boot.bind(port).sync();
            } else {
                log.info("Starting Netty TCP server on port {}", port);
                ServerBootstrap boot = new ServerBootstrap();
                boot.group(boss, wrkr)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(adapter)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .option(ChannelOption.SO_KEEPALIVE, true);
                sock = boot.bind(port).sync();
            }
            log.info("Netty server started");
            notifyStarted();
        } catch (InterruptedException e) {
            log.warn("Netty server start interrupted");
            Thread.currentThread().interrupt();
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        log.info("Stopping Netty server");
        boss.shutdownGracefully();
        if(!datagram) {
            wrkr.shutdownGracefully();
        }
        try {
            sock.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.warn("Netty server stop interrupted");
            Thread.currentThread().interrupt();
        }
        log.info("Netty server stopped");
        notifyStopped();
    }
}
