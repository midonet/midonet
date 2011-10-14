// Copyright 2011 Midokura Inc.

package com.midokura.midolman.eventloop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.Pipe;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import org.junit.Before;
import org.junit.Test;

public class TestSelectLoop {
    static class BooleanBox { volatile boolean value; }

    SelectLoop reactor;
    Pipe pipe1, pipe2;
    SelectableChannel channel1, channel2;
    ByteBuffer message;

    @Before
    public void setup() throws IOException {
        reactor = new SelectLoop(Executors.newScheduledThreadPool(1));
        pipe1 = Pipe.open();
        pipe2 = Pipe.open();
        channel1 = pipe1.source();
        channel2 = pipe2.source();
        channel1.configureBlocking(false);
        channel2.configureBlocking(false);
        message = ByteBuffer.allocate(8);
        message.putLong(0x0F00BA44DEADBEEFL);
    }

    @Test
    public void testRegisterDuringSelect() 
                throws InterruptedException, IOException {
        final BooleanBox noExceptions = new BooleanBox();
        noExceptions.value = false;
        Thread registerThread = new Thread(
            new Runnable() {
                SelectListener listener =
                        new SelectListener() {
                            @Override
                            public void handleEvent(SelectionKey key) { }
                        };
                public void run() {
                    try {
                        // Race with select()
                        reactor.register(channel1, SelectionKey.OP_READ,
                                         listener);
                        // Make sure we've entered the select() call.
                        Thread.sleep(100);
                        reactor.register(channel2, SelectionKey.OP_READ,
                                         listener);
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

    @Test
    public void testSubmitDuringEvent() 
            throws IOException, InterruptedException, ClosedChannelException {
        final BooleanBox submitHasRun = new BooleanBox();
        final BooleanBox somethingBroke = new BooleanBox();
        submitHasRun.value = false;
        somethingBroke.value = false;
        final SelectListener listener =
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key) {
                        try {
                            reactor.submit(
                                new Runnable() {
                                    public void run() { 
                                        submitHasRun.value = true; 
                                    }
                                });
                            Thread.sleep(10);
                            if (submitHasRun.value)
                                somethingBroke.value = true;
                        } catch (Exception e) {
                            somethingBroke.value = true;
                        }
                    }
                };
        reactor.register(channel1, SelectionKey.OP_READ, listener);
        pipe1.sink().write(message);
        Thread.sleep(10);
        assertTrue(submitHasRun.value);
        assertFalse(somethingBroke.value);
    }
}
