package com.midokura.midolman.eventloop;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Dirt simple SelectLoop for simple java controller
 *
 * Originally from org.openflowj.examples
 *
 */
public class SelectLoop implements Reactor {
    private static final Logger log = LoggerFactory.getLogger(SelectLoop.class);

    protected boolean dontStop;
    protected Selector selector;
    // The timeout value in milliseconds that select will be called with
    // (currently zero, may be settable in the future).
    protected long timeout;

    protected ScheduledExecutorService executor;

    public SelectLoop(ScheduledExecutorService executor) throws IOException {
        dontStop = true;
        selector = SelectorProvider.provider().openSelector();
        this.timeout = 0;

        this.executor = executor;
    }

    @Override
    public Future submit(final Runnable runnable) {
        return executor.submit(new Runnable() {
            @Override
            public void run() {
                synchronized (SelectLoop.this) {
                    runnable.run();
                }
            }
        });
    }

    @Override
    public ScheduledFuture schedule(final Runnable runnable, long delay,
                                    TimeUnit unit) {
        return executor.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (SelectLoop.this) {
                    runnable.run();
                }
            }
        }, delay, unit);
    }

    /**
     * Registers the supplied SelectableChannel with this SelectLoop.
     * @param ch the channel
     * @param ops interest ops
     * @param arg argument that will be returned with the SelectListener
     * @return SelectionKey
     * @throws ClosedChannelException if channel was already closed
     */
    public SelectionKey register(SelectableChannel ch, int ops,
                                 SelectListener arg)
            throws ClosedChannelException {
        SelectionKey key = ch.register(selector, ops, arg);
        return key;
    }

    /**
     * Main top-level IO loop this dispatches all IO events and timer events
     * together I believe this is fairly efficient
     **/
    public void doLoop() throws IOException {
        log.debug("doLoop");

        int nEvents;

        while (dontStop) {
            log.debug("looping");

            nEvents = selector.select(timeout);
            if (nEvents > 0) {
                for (SelectionKey sk : selector.selectedKeys()) {
                    if (!sk.isValid())
                        continue;

                    SelectListener callback = (SelectListener) sk.attachment();
                    synchronized (this) {
                        callback.handleEvent(sk);
                    }
                }
                selector.selectedKeys().clear();
            }

        }
    }

    /**
     * Force this select loop to return immediately and re-enter select, useful
     * for example if a new item has been added to the select loop while it
     * was already blocked.
     */
    public void wakeup() {
       if (selector != null) {
           selector.wakeup();
       }
    }

    /**
     * Shuts down this select loop, may return before it has fully shutdown
     */
    public void shutdown() {
        this.dontStop = false;
        wakeup();
    }

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
