/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.ini4j.ConfigParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.SelectListener;
import com.midokura.midolman.eventloop.SelectLoop;
import com.midokura.midolman.openflow.ControllerStubImpl;

public class Midolman {

    private static final Logger log = LoggerFactory.getLogger(Midolman.class);
    
    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        log.info("main");

        final ConfigParser config = new ConfigParser();
        config.read("./conf/midolman.conf");

        final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        final ServerSocketChannel listenSock = ServerSocketChannel.open();
        listenSock.configureBlocking(false);
        listenSock.socket().bind(new java.net.InetSocketAddress(config.getInt("openflow", "controller_port")));

        final SelectLoop loop = new SelectLoop();

        loop.register(listenSock, SelectionKey.OP_ACCEPT, new SelectListener() {

            @Override
            public void handleEvent(SelectionKey key) throws IOException {
                log.info("handleEvent " + key);

                SocketChannel sock = listenSock.accept();

                log.info("handleEvent acccepted connection from "
                        + sock.socket().getRemoteSocketAddress());

                sock.socket().setTcpNoDelay(true);
                sock.configureBlocking(false);

                ControllerStubImpl controllerStubImpl = new ControllerStubImpl(sock, executor,
                        new ControllerTrampoline());

                loop.registerBlocking(sock, SelectionKey.OP_READ, controllerStubImpl);
            }

        });

        log.debug("before doLoop");
        loop.doLoop();

    }

}
