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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.util.HashSet;
import java.util.Set;

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

public class SelectorThreadPair {
    private Logger log = LoggerFactory.getLogger(this.getClass());

    public final String name;

    private final MidolmanConfig config;

    private Thread readThread;
    private Thread writeThread;
    private SelectLoop readLoop;
    private SelectLoop writeLoop;
    private final boolean singleThreaded;

    private Set<ManagedDatapathConnection> conns = new HashSet<>();

    public SelectorThreadPair(String name, MidolmanConfig config,
                              boolean singleThreaded) {
        this.config = config;
        this.name = name;
        this.singleThreaded = singleThreaded;
    }

    public SelectorThreadPair(String name, MidolmanConfig config) {
        this(name, config, false);
    }

    public boolean isRunning() {
        return (readThread != null);
    }

    public void start() throws Exception {
        if (readThread == null) {
            try {
                setUp();
            } catch (Exception e) {
                stop();
                throw e;
            }
        }
    }

    public SelectLoop getReadLoop() {
        return readLoop;
    }

    public SelectLoop getWriteLoop() {
        return writeLoop;
    }

    public ManagedDatapathConnection addConnection(final Bucket bucket,
            final BufferPool sendPool, SelectLoop.Priority priority) throws Exception {

        final OvsDatapathConnection conn =
            OvsDatapathConnection.create(new Netlink.Address(0), sendPool);

        conn.getChannel().configureBlocking(false);
        conn.setMaxBatchIoOps(config.getMaxMessagesPerBatch());

        readLoop.register(
                conn.getChannel(),
                SelectionKey.OP_READ,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                            throws IOException {
                        conn.handleReadEvent(bucket);
                    }
                }, priority);

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
                }, priority);

        ManagedDatapathConnection managedConn =
                new TrivialDatapathConnection(conn);

        conns.add(managedConn);
        return managedConn;
    }

    private void closeConnection(ManagedDatapathConnection conn) {
        try {
            if (writeLoop != null) {
                writeLoop.unregister(conn.getConnection().getChannel(),
                        SelectionKey.OP_WRITE);
            }
        } catch (ClosedChannelException ignored) {}
        try {
            if (readLoop != null) {
                readLoop.unregister(conn.getConnection().getChannel(),
                        SelectionKey.OP_READ);
            }
        } catch (ClosedChannelException ignored) {}

        try {
            conn.getConnection().getChannel().close();
        } catch (IOException ignored) {}
    }

    public void removeConnection(ManagedDatapathConnection conn) {
        if (conns.remove(conn))
            closeConnection(conn);
    }

    private void setUp() throws Exception {
        if (readThread != null)
            return;

        log.info("Starting selector thread pair: {}", name);
        readLoop = new SimpleSelectLoop();
        writeLoop = singleThreaded ? readLoop : new SimpleSelectLoop();

        readThread = startLoop(readLoop, name + (singleThreaded ? "" : ".read"));
        writeThread = singleThreaded ? readThread :
                                       startLoop(writeLoop, name + ".write");
    }

    public void stop() throws Exception {
        try {
            log.info("Stopping selector thread pair: {}", name);

            for (ManagedDatapathConnection conn: conns)
                closeConnection(conn);
            conns.clear();

            if (readLoop != null)
                readLoop.shutdown();

            if (!singleThreaded) {
                if (writeLoop != null)
                    writeLoop.shutdown();
            }

        } finally {
            conns.clear();
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
