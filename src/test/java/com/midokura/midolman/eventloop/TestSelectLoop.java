// Copyright 2011 Midokura Inc.

package com.midokura.midolman.eventloop;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import static org.junit.Assert.fail;
import org.junit.Test;

public class TestSelectLoop implements SelectListener {

    @Test
    public void testRegisterDuringSelect()
                throws IOException, InterruptedException {
        final SelectLoop reactor =
                    new SelectLoop(Executors.newScheduledThreadPool(1));
        final Semaphore sem = new Semaphore(0);
        Thread registerThread = new Thread(
            new Runnable() {
                public void run() {
                    try {
                        // Make sure we've entered the select() call.
                        Thread.sleep(100);
                        SelectableChannel socket = ServerSocketChannel.open();
                        socket.configureBlocking(false);
                        reactor.register(socket, SelectionKey.OP_ACCEPT,
                                         TestSelectLoop.this);
                        fail("register should have thrown");
                    } catch (Exception e) {}
                    reactor.shutdown();
                }
            });
        registerThread.start();
        reactor.doLoop();

        registerThread.join();
    }

    @Override
    public void handleEvent(SelectionKey key) { }
}
