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
package org.midonet.midolman.io;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Netlink;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.Bucket;
import org.midonet.util.eventloop.SelectListener;
import org.midonet.util.eventloop.SelectLoop;
import org.midonet.util.eventloop.SimpleSelectLoop;

public class SelectorBasedDatapathConnection implements ManagedDatapathConnection {
    private final static Logger log =
        LoggerFactory.getLogger(SelectorBasedDatapathConnection.class);

    public final String name;

    private final MidolmanConfig config;

    private Thread readThread;
    private Thread writeThread;
    private SelectLoop readLoop;
    private SelectLoop writeLoop;
    private BufferPool sendPool;
    private OvsDatapathConnection conn = null;
    private final boolean singleThreaded;
    private final Bucket bucket;

    public SelectorBasedDatapathConnection(String name,
                                           MidolmanConfig config,
                                           boolean singleThreaded,
                                           Bucket bucket,
                                           BufferPool sendPool) {
        this.config = config;
        this.name = name;
        this.singleThreaded = singleThreaded;
        this.bucket = bucket;
        this.sendPool = sendPool;
    }

    public SelectorBasedDatapathConnection(String name,
                                           MidolmanConfig config,
                                           boolean singleThreaded,
                                           Bucket bucket) {
        this(name, config, singleThreaded, bucket,
             new BufferPool(config.datapath().sendBufferPoolInitialSize(),
                            config.datapath().sendBufferPoolMaxSize(),
                            config.datapath().sendBufferPoolBufSizeKb() * 1024));
    }

    public SelectorBasedDatapathConnection(String name, MidolmanConfig config) {
        this(name, config, false, Bucket.BOTTOMLESS);
    }

    public OvsDatapathConnection getConnection() {
        return conn;
    }

    @Override
    public void start() throws IOException, ExecutionException, InterruptedException {
        if (conn == null) {
            try {
                setUp();
            } catch (IOException e) {
                try {
                    stop();
                } catch (Exception ignored) {}
                throw e;
            }
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
        conn.setMaxBatchIoOps(200); // FIXME - deprecated

        readLoop.register(
                conn.getChannel(),
                SelectionKey.OP_READ,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                            throws IOException {
                        conn.handleReadEvent(bucket);
                    }
                }, SelectLoop.Priority.NORMAL);

        writeLoop.registerForInputQueue(
                conn.getSendQueue(),
                conn.getChannel(),
                SelectionKey.OP_WRITE,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                            throws IOException {
                        conn.handleWriteEvent();
                    }
                }, SelectLoop.Priority.NORMAL);
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
        th.setName(threadName);
        th.setDaemon(true);
        th.start();
        return th;
    }
}
