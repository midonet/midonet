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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class TestSelectLoop {
    static class BooleanBox { volatile boolean value; }

    private final static Logger log =
                        LoggerFactory.getLogger(TestSelectLoop.class);

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
        message.rewind();
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
        log.debug("Entering testSubmitDuringEvent");
        final BooleanBox submitHasRun = new BooleanBox();
        final BooleanBox somethingBroke = new BooleanBox();
        submitHasRun.value = false;
        somethingBroke.value = false;
        final SelectListener listener =
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key) {
                        log.debug("testSubmitDuringEvent: " +
                                  "entering handleEvent()");
                        try {
                            reactor.submit(
                                new Runnable() {
                                    public void run() { 
                                        submitHasRun.value = true; 
                                    }
                                });
                            ByteBuffer recvbuf = ByteBuffer.allocate(8);
                            pipe1.source().read(recvbuf);
                            Thread.sleep(15);
                            log.debug("Reactor thread waking up");
                            if (submitHasRun.value)
                                somethingBroke.value = true;
                        } catch (Exception e) {
                            somethingBroke.value = true;
                        }
                    }
                };
        new Thread(new Runnable() { 
                       public void run() { 
                           log.debug("Entering reactor thread");
                           try { reactor.doLoop(); }
                           catch (Exception e) { 
                               somethingBroke.value = true; 
                           }
                       }
                   }).start();
        reactor.register(channel1, SelectionKey.OP_READ, listener);
        log.debug("About to write {}", message);
        pipe1.sink().write(message);
        log.debug("Just wrote {}", message);
        Thread.sleep(30);   // Let the sleep() in handleEvent() finish.
        log.debug("Main thread waking up");
        assertTrue(submitHasRun.value);
        assertFalse(somethingBroke.value);
    }
}
