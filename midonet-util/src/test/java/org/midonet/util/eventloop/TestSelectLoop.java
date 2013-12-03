// Copyright 2011 Midokura Inc.

package org.midonet.util.eventloop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.ScatteringByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


public class TestSelectLoop {

    static class BooleanBox {
        volatile boolean value;
        public BooleanBox(Boolean initial) { this.value = initial; }
    }

    private final static Logger log =
                        LoggerFactory.getLogger(TestSelectLoop.class);

    SelectLoop loop;
    Reactor reactor;
    Pipe pipe1, pipe2;
    SelectableChannel channel1, channel2;
    ByteBuffer message;
    BooleanBox reactorThrew;
    BooleanBox reactorFinished;

    @Before
    public void setup() {
        try {
            log.info("Eventloop set-up starting.");
            loop = new SimpleSelectLoop();
            reactor = new TryCatchReactor("test", 1);
            assertTrue(reactor != null);
            pipe1 = Pipe.open();
            pipe2 = Pipe.open();
            channel1 = pipe1.source();
            channel2 = pipe2.source();
            channel1.configureBlocking(false);
            channel2.configureBlocking(false);
            message = ByteBuffer.allocate(8);
            message.putLong(0x0F00BA44DEADBEEFL);
            message.rewind();
            reactorThrew = new BooleanBox(false);
            reactorFinished = new BooleanBox(false);
            log.info("Eventloop set-up complete.");
        } catch (Throwable e) {
            log.error("Aiee, exception setting up eventloop: ", e);
            assertTrue(false);
        }
    }

    @After
    public void teardown() throws IOException {
        log.info("Eventloop tear-down starting.");
        assertTrue(reactor != null);
        reactor.shutDownNow();
        loop.shutdown();
        pipe1.sink().close();
        pipe1.source().close();
        pipe2.sink().close();
        pipe2.source().close();
    }

    private void waitOnValue(AtomicInteger counter, int target, int retry)
            throws InterruptedException {
        while (retry >= 0) {
            if (counter.get() == target)
                return;
            Thread.sleep(20);
            retry -= 1;
        }
        assertTrue(false);
    }

    abstract class Handler implements Runnable, SelectListener {
        final BooleanBox somethingBroke;
        final AtomicInteger eventCounter;

        protected SelectionKey key;

        public Handler(AtomicInteger eventCounter, BooleanBox notifier) {
            this.somethingBroke = notifier;
            this.eventCounter = eventCounter;
        }

        abstract public void action() throws Exception;

        public void run() {
            try {
                while(!eventCounter.compareAndSet(0,1)) {
                    Thread.sleep(30);
                }
                action();
            } catch (Exception e) {
                somethingBroke.value = true;
            }
        }

        public void handleEvent(SelectionKey key) {
            this.key = key;
            run();
        }
    }

    private Thread startReactorThread() {
        Thread reactorThread = new Thread(new Runnable() {
                                   public void run() {
                                       log.debug("Entering reactor thread");
                                       try {
                                           loop.doLoop();
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
        final BooleanBox noExceptions = new BooleanBox(false);
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
                        loop.register(channel1, SelectionKey.OP_READ,
                                         listener);
                        // Make sure we've entered the select() call.
                        Thread.sleep(100);
                        loop.register(channel2, SelectionKey.OP_READ,
                                         listener);
                        noExceptions.value = true;
                    } catch (Exception e) {
                        System.err.println("Caught exception " + e);
                        e.printStackTrace();
                    } finally {
                        loop.shutdown();
                    }
                }
            });
        registerThread.start();
        loop.doLoop();

        registerThread.join();
        assertTrue(noExceptions.value);
    }

    @Test
    public void testEventDuringEvent()
                throws IOException, InterruptedException {

        final AtomicInteger eventCounter = new AtomicInteger(0);
        final BooleanBox somethingBroke = new BooleanBox(false);

        class SelectResponder extends Handler {
            public SelectResponder(AtomicInteger counter, BooleanBox notifier) {
                super(counter, notifier);
            }
            @Override public void action() throws Exception {
                ScatteringByteChannel channel =
                            (ScatteringByteChannel) key.channel();
                channel.read(ByteBuffer.allocate(8));
            }
        }

        SelectListener listener1 = new SelectResponder(eventCounter, somethingBroke);
        SelectListener listener2 = new SelectResponder(eventCounter, somethingBroke);

        pipe1.sink().write(message);
        message.rewind();
        pipe2.sink().write(message);
        loop.register(channel1, SelectionKey.OP_READ, listener1);
        loop.register(channel2, SelectionKey.OP_READ, listener2);

        startReactorThread();

        waitOnValue(eventCounter, 1, 10);

        assertTrue(eventCounter.get() == 1);
        int reset = eventCounter.decrementAndGet();
        assertTrue(reset == 0);

        waitOnValue(eventCounter, 1, 10);
        assertTrue(eventCounter.get() == 1);
        assertFalse(reactorFinished.value);

        loop.shutdown();
        reactor.shutDownNow();
        Thread.sleep(30);
        assertTrue(reactorFinished.value);
        assertFalse(somethingBroke.value);
        assertFalse(reactorThrew.value);
    }

    @Test
    public void testSubmitDuringSubmit() throws InterruptedException {

        final AtomicInteger eventCounter = new AtomicInteger(0);
        final BooleanBox somethingBroke = new BooleanBox(false);

        class Submission extends Handler {
            public Submission(AtomicInteger counter, BooleanBox notifier) {
                super(counter, notifier);
            }
            @Override public void action() throws Exception { }
        }


        Submission submission1 = new Submission(eventCounter, somethingBroke);
        Submission submission2 = new Submission(eventCounter, somethingBroke);
        reactor.submit(submission1);
        reactor.submit(submission2);

        // TODO(dmd): Are these supposed to have already run, though the
        // Reactor loop wasn't started?
        //Thread.sleep(15);
        //assertFalse(submit1HasRun.value);
        //assertFalse(submit2HasRun.value);

        waitOnValue(eventCounter, 1, 10);

        assertTrue(eventCounter.get() == 1);
        eventCounter.decrementAndGet();
        assertTrue(eventCounter.get() == 0);

        waitOnValue(eventCounter, 1, 10);
        assertTrue(eventCounter.get() == 1);
    }
}
