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
import org.junit.After;
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
    BooleanBox reactorThrew;
    BooleanBox reactorFinished;

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
        reactorThrew = new BooleanBox();
        reactorThrew.value = false;
        reactorFinished = new BooleanBox();
        reactorFinished.value = false;
    }

    @After
    public void teardown() throws IOException {
        reactor.shutdown();
        pipe1.sink().close();
        pipe1.source().close();
        pipe2.sink().close();
        pipe2.source().close();
    }

    private Thread startReactorThread() {
        Thread reactorThread = new Thread(new Runnable() { 
                                   public void run() { 
                                       log.debug("Entering reactor thread");
                                       try { 
                                           reactor.doLoop();
                                           reactorFinished.value = true;
                                       } catch (Exception e) { 
                                           reactorThrew.value = true; 
                                       }
                                   }
                               });
        reactorThread.start();
        return reactorThread;
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
        startReactorThread();
        reactor.register(channel1, SelectionKey.OP_READ, listener);
        log.debug("About to write {}", message);
        pipe1.sink().write(message);
        log.debug("Just wrote {}", message);
        Thread.sleep(30);   // Let the sleep() in handleEvent() finish.
        log.debug("Main thread waking up");
        assertTrue(submitHasRun.value);
        assertFalse(somethingBroke.value);
        assertFalse(reactorThrew.value);
        assertFalse(reactorFinished.value);
    }

    @Test
    public void testEventDuringSubmit() 
                throws ClosedChannelException, InterruptedException {
        final BooleanBox eventHasRun = new BooleanBox();
        final BooleanBox somethingBroke = new BooleanBox();
        eventHasRun.value = false;
        somethingBroke.value = false;
        startReactorThread();
        
        SelectListener listener = 
                        new SelectListener() {
                            public void handleEvent(SelectionKey key) {
                                try {
                                    eventHasRun.value = true;
                                    ByteBuffer recvbuf = ByteBuffer.allocate(8);
                                    pipe1.source().read(recvbuf);
                                } catch (Exception e) {
                                    somethingBroke.value = true;
                                }
                            }
                        };
        reactor.register(channel1, SelectionKey.OP_READ, listener);

        reactor.submit(new Runnable() {
                           public void run() {
                               try {
                                   pipe1.sink().write(message);
                                   Thread.sleep(15);
                                   if (eventHasRun.value)
                                       somethingBroke.value = true;
                               } catch (Exception e) {
                                   somethingBroke.value = true;
                               }
                           }
                       });
        Thread.sleep(30);
        assertTrue(eventHasRun.value);
        assertFalse(somethingBroke.value);
        assertFalse(reactorThrew.value);
        assertFalse(reactorFinished.value);
    }
}
