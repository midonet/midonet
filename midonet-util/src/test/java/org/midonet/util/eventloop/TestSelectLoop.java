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

package org.midonet.util.eventloop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.ScatteringByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


public class TestSelectLoop {

    private final static Logger log =
                        LoggerFactory.getLogger(TestSelectLoop.class);

    SelectLoop loop;
    Reactor reactor;
    Pipe pipe1, pipe2;
    SelectableChannel channel1, channel2;
    ByteBuffer message;

    @Before
    public void setup() {
        try {
            log.info("Eventloop set-up starting.");
            loop = new SimpleSelectLoop();
            reactor = new TryCatchReactor("test", 1);
            pipe1 = Pipe.open();
            pipe2 = Pipe.open();
            channel1 = pipe1.source();
            channel2 = pipe2.source();
            channel1.configureBlocking(false);
            channel2.configureBlocking(false);
            message = ByteBuffer.allocate(8);
            message.putLong(0x0F00BA44DEADBEEFL);
            message.rewind();
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

    private void waitOnValue(Semaphore sem, int permits)
            throws InterruptedException {
        sem.tryAcquire(permits, 10, TimeUnit.SECONDS);
    }

    abstract class Handler implements Runnable, SelectListener {
        final Semaphore sem;
        public volatile Exception failure;

        protected SelectionKey key;

        public Handler(Semaphore sem) {
            this.sem = sem;
        }

        abstract public void action() throws Exception;

        public void run() {
            sem.release();
            try {
                action();
            } catch (Exception e) {
                failure = e;
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
                } catch (IOException e) {
                }
            }
        });
        reactorThread.setDaemon(true);
        reactorThread.start();
        return reactorThread;
    }

    @Test
    public void testRegisterDuringSelect() throws Throwable {
        final Throwable[] exceptions = { null };
        final Semaphore sem = new Semaphore(0);
        final SelectListener listener = new SelectListener() {
            @Override
            public void handleEvent(SelectionKey key) {
                sem.release();
            }
        };
        Thread registerThread = new Thread(new Runnable() {
            public void run() {
                try {
                    // Make sure we've entered the select() call.
                    boolean acquired = sem.tryAcquire(10, TimeUnit.SECONDS);
                    assertTrue(acquired);
                    loop.register(channel2, SelectionKey.OP_READ,
                            listener, SelectLoop.Priority.NORMAL);
                } catch (Throwable e) {
                    exceptions[0] = e;
                } finally {
                    loop.shutdown();
                }
            }
        });
        registerThread.start();
        loop.register(channel1, SelectionKey.OP_READ,
                      listener, SelectLoop.Priority.NORMAL);
        pipe1.sink().write(message);
        loop.doLoop();

        registerThread.join();

        if (exceptions[0] != null)
            throw exceptions[0];
    }

    @Test
    public void testEventDuringEvent() throws IOException, InterruptedException {

        final Semaphore sem = new Semaphore(0);

        class SelectResponder extends Handler {
            public SelectResponder(Semaphore sem) {
                super(sem);
            }
            @Override public void action() throws Exception {
                ScatteringByteChannel channel =
                            (ScatteringByteChannel) key.channel();
                channel.read(ByteBuffer.allocate(8));
            }
        }

        SelectResponder listener1 = new SelectResponder(sem);
        SelectResponder listener2 = new SelectResponder(sem);

        pipe1.sink().write(message);
        message.rewind();
        pipe2.sink().write(message);
        loop.register(channel1, SelectionKey.OP_READ,
                      listener1, SelectLoop.Priority.NORMAL);
        loop.register(channel2, SelectionKey.OP_READ,
                      listener2, SelectLoop.Priority.NORMAL);

        Thread reactorThread = startReactorThread();

        waitOnValue(sem, 2);

        loop.shutdown();
        reactor.shutDownNow();
        reactorThread.join(10000);
        assertNull(listener1.failure);
        assertNull(listener2.failure);
    }

    @Test
    public void testSubmitDuringSubmit() throws InterruptedException {
        final Semaphore sem = new Semaphore(0);

        class Submission extends Handler {
            public Submission(Semaphore sem) {
                super(sem);
            }
            @Override public void action() throws Exception { }
        }

        Submission submission1 = new Submission(sem);
        Submission submission2 = new Submission(sem);
        reactor.submit(submission1);
        reactor.submit(submission2);

        waitOnValue(sem, 2);
        assertNull(submission1.failure);
        assertNull(submission2.failure);
    }

    @Test
    public void testPriority() throws Exception {
        final List<Object> eventOrder = new ArrayList<Object>();
        final SelectListener listener1 = new SelectListener() {
            @Override
            public void handleEvent(SelectionKey key) {
                synchronized (eventOrder) {
                    log.info("IKDEBUG listener1");
                    eventOrder.add(this);
                }
            }
        };
        final SelectListener listener2 = new SelectListener() {
            @Override
            public void handleEvent(SelectionKey key) {
                synchronized (eventOrder) {
                    log.info("IKDEBUG listener2");
                    eventOrder.add(this);
                }
            }
        };

        // register normal priority first
        loop.register(channel1, SelectionKey.OP_READ,
                      listener1, SelectLoop.Priority.NORMAL);
        loop.register(channel2, SelectionKey.OP_READ,
                      listener2, SelectLoop.Priority.HIGH);
        message.rewind();
        pipe1.sink().write(message);
        message.rewind();
        pipe2.sink().write(message);
        ((SimpleSelectLoop)loop).doLoopOnce();
        assertTrue(eventOrder.get(0) == listener2);
        assertTrue(eventOrder.get(1) == listener1);

        loop.unregister(channel1, SelectionKey.OP_READ);
        loop.unregister(channel2, SelectionKey.OP_READ);
        eventOrder.clear();

        // register high priority first
        loop.register(channel1, SelectionKey.OP_READ,
                      listener1, SelectLoop.Priority.HIGH);
        loop.register(channel2, SelectionKey.OP_READ,
                      listener2, SelectLoop.Priority.NORMAL);
        message.rewind();
        pipe1.sink().write(message);
        message.rewind();
        pipe2.sink().write(message);
        ((SimpleSelectLoop)loop).doLoopOnce();
        assertTrue(eventOrder.get(0) == listener1);
        assertTrue(eventOrder.get(1) == listener2);
    }
}
