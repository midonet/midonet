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

import java.net.InetSocketAddress;

import com.google.common.util.concurrent.AbstractService;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain netty-based client service. This class is *NOT* thread-safe.
 */
public class ClientFrontEnd extends AbstractService {
    private static final Logger log =
        LoggerFactory.getLogger(ClientFrontEnd.class);

    private final String host;
    private final int port;
    private final Boolean datagram;
    private final EventLoopGroup wrkr = new NioEventLoopGroup();
    private ChannelFuture sock;

    private final ChannelInboundHandlerAdapter adapter;

    public enum State {initialized, connected, disconnected, error};
    private State clientState = State.initialized;

    public ClientFrontEnd(ChannelInboundHandlerAdapter adapter,
                          String host, int port, boolean datagram) {
        this.adapter = adapter;
        this.host = host;
        this.port = port;
        this.datagram = datagram;
    }

    @Override
    protected void doStart() {
        if (clientState == State.initialized) {
            log.info("Starting Netty client for {}:{}", host, port);
            try {
                Bootstrap boot = new Bootstrap();

                if (datagram) {
                    boot.group(wrkr)
                        .channel(NioDatagramChannel.class)
                        .handler(adapter);
                    sock = boot.bind(0).sync();
                } else {
                    boot.group(wrkr)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.SO_KEEPALIVE, true)
                        .handler(adapter);
                    sock = boot.connect(host, port).sync();
                }
                log.info("Netty client started");
                clientState = State.connected;
                notifyStarted();
            } catch (InterruptedException e) {
                log.warn("Netty client start interrupted");
                Thread.currentThread().interrupt();
                clientState = State.error;
                notifyFailed(e);
            }
        } else {
            log.warn("Netty client is in state: " + clientState + " and can "
                     + "thus not be started");
        }
    }

    @Override
    protected void doStop() {
        if (clientState == State.connected) {
            log.info("Stopping Netty client");
            wrkr.shutdownGracefully();
            try {
                sock.channel().closeFuture().sync();
            } catch (InterruptedException e) {
                log.warn("Netty client stop interrupted");
                Thread.currentThread().interrupt();
            }
            log.info("Netty client stopped");
            clientState = State.disconnected;
            notifyStopped();
        } else {
            log.warn("Netty client is in state: " + clientState + " and can "
                     + "thus not be stopped");
        }
    }


    public void send(ByteBuf msg) throws InterruptedException {
        if (clientState == State.connected) {
            if (datagram) {
                DatagramPacket packet = new DatagramPacket(msg,
                    new InetSocketAddress(host, port));
                sock.channel().writeAndFlush(packet).sync();
            } else {
                // TODO
            }
        } else {
            throw new IllegalStateException("Netty client is not connected");
        }
    }
}
