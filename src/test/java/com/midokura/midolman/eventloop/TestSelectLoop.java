// Copyright 2011 Midokura Inc.

package com.midokura.midolman.eventloop;

import java.io.IOException;
import java.nio.channels.Pipe;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TestSelectLoop implements SelectListener {
    static class BooleanBox { volatile boolean value; }

    @Test
    public void testRegisterDuringSelect()
                throws IOException, InterruptedException {
        final SelectLoop reactor =
                    new SelectLoop(Executors.newScheduledThreadPool(1));
        final BooleanBox noExceptions = new BooleanBox();
        Pipe pipe1 = Pipe.open();
        Pipe pipe2 = Pipe.open();
        final SelectableChannel channel1 = pipe1.source();
        final SelectableChannel channel2 = pipe2.source();
        channel1.configureBlocking(false);
        channel2.configureBlocking(false);
        noExceptions.value = false;
        Thread registerThread = new Thread(
            new Runnable() {
                public void run() {
                    try {
                        // Race with select()
                        reactor.register(channel1, SelectionKey.OP_READ,
                                         TestSelectLoop.this);
                        // Make sure we've entered the select() call.
                        Thread.sleep(100);
                        reactor.register(channel2, SelectionKey.OP_READ,
                                         TestSelectLoop.this);
                        noExceptions.value = true;
                    } catch (Exception e) {
                        System.err.println("Caught exception " + e);
                        e.printStackTrace();
                    } finally {
                        reactor.shutdown();
                    }
                }
            });
        registerThread.start();
        reactor.doLoop();

        registerThread.join();
        assertTrue(noExceptions.value);
    }

    @Override
    public void handleEvent(SelectionKey key) { }
}
