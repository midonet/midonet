/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.netlink.Callback;
import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Netlink;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.odp.protos.OvsDatapathConnection;


public class BlockingTransactorDatapathConnection implements ManagedDatapathConnection {
    private Logger log = LoggerFactory.getLogger(this.getClass());

    public String name;

    private MidolmanConfig config;

    private Thread thread;
    private BufferPool sendPool;
    private OvsDatapathConnection conn = null;

    public BlockingTransactorDatapathConnection(String name,
                                                MidolmanConfig config) {
        this.config = config;
        this.name = name;
        this.sendPool = new BufferPool(config.getSendBufferPoolInitialSize(),
                                       config.getSendBufferPoolMaxSize(),
                                       config.getSendBufferPoolBufSizeKb() * 1024);
    }

    @Override
    public OvsDatapathConnection getConnection() {
        return conn;
    }

    @Override
    public void start(Callback<Boolean> cb) {
        try {
            setUp();
            conn.initialize(cb);
        } catch (Exception e) {
            try {
                stop();
            } catch (Exception ignored) {}
            cb.onError(new NetlinkException(NetlinkException.GENERIC_IO_ERROR, e));
        }
    }

    @Override
    public void start() throws IOException, InterruptedException, ExecutionException {
        if (conn != null)
            return;

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

    private void setUp() throws IOException {
        log.info("Starting datapath connection: {}", name);
        conn = OvsDatapathConnection.create(new Netlink.Address(0), sendPool);
        conn.getChannel().configureBlocking(true);
        conn.setMaxBatchIoOps(config.getMaxMessagesPerBatch());
        this.thread = startTransactorThread(name);
    }

    public void stop() throws Exception {
        log.info("Stopping datapath connection: {}", name);
        try {
            conn.getChannel().close();
        } catch (IOException e) {}
    }

    private Thread startTransactorThread(final String threadName) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                while (conn.getChannel().isConnected()) {
                    try {
                        conn.doTransactionBatch();
                    } catch (InterruptedException e) {
                    }
                }
            }
        });

        log.info("Starting datapath transactor thread: {}", threadName);
        th.start();
        th.setName(threadName);
        return th;
    }
}
