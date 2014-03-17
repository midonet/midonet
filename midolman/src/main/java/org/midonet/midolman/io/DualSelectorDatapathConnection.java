/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io;

import java.io.IOException;
import java.nio.channels.SelectionKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Netlink;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.SelectListener;
import org.midonet.util.eventloop.SelectLoop;
import org.midonet.util.eventloop.SimpleSelectLoop;


public class DualSelectorDatapathConnection implements ManagedDatapathConnection {
    public final String name;

    private MidolmanConfig config;

    private Reactor reactor;

    private Thread readThread;
    private Thread writeThread;
    private SelectLoop readLoop;
    private SelectLoop writeLoop;
    private BufferPool sendPool;
    private OvsDatapathConnection conn = null;
    private boolean singleThreaded;

    public DualSelectorDatapathConnection(String name, Reactor reactor,
                                          MidolmanConfig config,
                                          boolean singleThreaded) {
        this.config = config;
        this.reactor = reactor;
        this.name = name;
        this.singleThreaded = singleThreaded;
        this.sendPool = new BufferPool(config.getSendBufferPoolInitialSize(),
                                       config.getSendBufferPoolMaxSize(),
                                       config.getSendBufferPoolBufSizeKb() * 1024);
    }

    public DualSelectorDatapathConnection(String name, Reactor reactor,
                                          MidolmanConfig config) {
        this(name, reactor, config, false);
    }

    public OvsDatapathConnection getConnection() {
        return conn;
    }

    public void start() throws Exception {
        if (conn != null)
            return;

        readLoop = new SimpleSelectLoop();
        writeLoop = singleThreaded ? readLoop : new SimpleSelectLoop();

        readThread = startLoop(readLoop, name + (singleThreaded ? "" : ".read"));
        writeThread = singleThreaded ? readThread :
                                       startLoop(writeLoop, name + ".write");

        conn = OvsDatapathConnection.create(new Netlink.Address(0), reactor, sendPool);

        conn.getChannel().configureBlocking(false);
        conn.setMaxBatchIoOps(config.getMaxMessagesPerBatch());

        readLoop.register(
                conn.getChannel(),
                SelectionKey.OP_READ,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                            throws IOException {
                        conn.handleReadEvent(key);
                    }
                });

        writeLoop.registerForInputQueue(
                conn.getSendQueue(),
                conn.getChannel(),
                SelectionKey.OP_WRITE,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                            throws IOException {
                        conn.handleWriteEvent(key);
                    }
                });

        conn.initialize().get();
    }

    public void stop() throws Exception {
        readLoop.unregister(conn.getChannel(), SelectionKey.OP_READ);
        writeLoop.unregister(conn.getChannel(), SelectionKey.OP_WRITE);
        readLoop.shutdown();
        if (!singleThreaded) {
            writeLoop.shutdown();
        }
    }

    private Thread startLoop(final SelectLoop loop, String threadName) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                Logger log = LoggerFactory.getLogger(DualSelectorDatapathConnection.class);
                try {
                    loop.doLoop();
                } catch (IOException e) {
                    log.error("IOException on netlink channel, ABORTING {}", name, e);
                    System.exit(2);
                }
            }
        });

        th.start();
        th.setName(threadName);
        return th;
    }
}
