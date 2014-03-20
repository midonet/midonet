/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Callback;
import org.midonet.netlink.Netlink;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.SelectListener;
import org.midonet.util.eventloop.SelectLoop;
import org.midonet.util.eventloop.SimpleSelectLoop;
import org.midonet.util.TokenBucket;


public class SelectorBasedDatapathConnection implements ManagedDatapathConnection {
    private Logger log = LoggerFactory.getLogger(this.getClass());

    public final String name;

    private final MidolmanConfig config;

    private Thread readThread;
    private Thread writeThread;
    private SelectLoop readLoop;
    private SelectLoop writeLoop;
    private BufferPool sendPool;
    private OvsDatapathConnection conn = null;
    private final boolean singleThreaded;
    private final TokenBucket tb;

    public SelectorBasedDatapathConnection(String name,
                                           MidolmanConfig config,
                                           boolean singleThreaded,
                                           TokenBucket tb) {
        this.config = config;
        this.name = name;
        this.singleThreaded = singleThreaded;
        this.tb = tb;
        this.sendPool = new BufferPool(config.getSendBufferPoolInitialSize(),
                                       config.getSendBufferPoolMaxSize(),
                                       config.getSendBufferPoolBufSizeKb() * 1024);
    }

    public SelectorBasedDatapathConnection(String name, MidolmanConfig config) {
        this(name, config, false, null);
    }

    public OvsDatapathConnection getConnection() {
        return conn;
    }

    @Override
    public void start() throws IOException, ExecutionException, InterruptedException {
        if (conn == null) {
            try {
                setUp();
                conn.initialize().get();
            } catch (IOException e) {
                try {
                    stop();
                } catch (Exception ignored) {}
                throw e;
            }
        }
    }

    @Override
    public void start(Callback<Boolean> cb) {
        if (conn != null) {
            cb.onSuccess(true);
            return;
        }

        try {
            setUp();
            conn.initialize(cb);
        } catch (Exception e) {
            try {
                stop();
            } catch (Exception ignored) { }
            cb.onError(new NetlinkException(NetlinkException.GENERIC_IO_ERROR, e));
        }
    }

    private void setUp() throws IOException {
        if (conn != null)
            return;

        log.info("Starting datapath connection: {}", name);
        readLoop = new SimpleSelectLoop();
        writeLoop = singleThreaded ? readLoop : new SimpleSelectLoop();

        readThread = startLoop(readLoop, name + (singleThreaded ? "" : ".read"));
        writeThread = singleThreaded ? readThread :
                                       startLoop(writeLoop, name + ".write");

        conn = OvsDatapathConnection.create(new Netlink.Address(0), sendPool);

        conn.getChannel().configureBlocking(false);
        conn.setMaxBatchIoOps(config.getMaxMessagesPerBatch());

        readLoop.register(
                conn.getChannel(),
                SelectionKey.OP_READ,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                            throws IOException {
                        conn.handleReadEvent(tb);
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
    }


    public void stop() throws Exception {
        try {
            log.info("Stopping datapath connection: {}", name);
            if (writeLoop != null)
                writeLoop.unregister(conn.getChannel(), SelectionKey.OP_WRITE);

            if (readLoop != null) {
                readLoop.unregister(conn.getChannel(), SelectionKey.OP_READ);
                readLoop.shutdown();
            }

            if (!singleThreaded) {
                if (writeLoop != null)
                    writeLoop.shutdown();
            }

            conn.getChannel().close();
        } finally {
            conn = null;
            writeLoop = null;
            readLoop = null;
            readThread = null;
            writeThread = null;
        }
    }

    private Thread startLoop(final SelectLoop loop, final String threadName) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loop.doLoop();
                } catch (IOException e) {
                    log.error("IOException on netlink channel, ABORTING {}", name, e);
                    System.exit(2);
                }
            }
        });

        log.info("Starting datapath select loop thread: {}", threadName);
        th.start();
        th.setName(threadName);
        return th;
    }
}
