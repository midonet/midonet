/*
Copyright (c) 2008 The Board of Trustees of The Leland Stanford Junior
University

We are making the OpenFlow specification and associated documentation
(Software) available for public use and benefit with the expectation that
others will use, modify and enhance the Software and contribute those
enhancements back to the community. However, since we would like to make the
Software available for broadest use, with as few restrictions as possible
permission is hereby granted, free of charge, to any person obtaining a copy of
this Software to deal in the Software under the copyrights without restriction,
including without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to permit
persons to whom the Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

The name and trademarks of copyright holder(s) may NOT be used in advertising
or publicity pertaining to the Software or any derivatives without specific,
written prior permission.
*/
package com.midokura.util.eventloop;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Dirt simple SelectLoop for simple java controller
 * <p/>
 * Originally from org.openflowj.examples
 */
public class SelectLoop implements Reactor {

    private static final Logger log = LoggerFactory.getLogger(SelectLoop.class);

    protected boolean dontStop;
    protected Selector selector;
    // The timeout value in milliseconds that select will be called with
    // (currently zero, may be settable in the future).
    protected long timeout;
    protected final Object registerLock = new Object();

    protected ScheduledExecutorService executor;

    public SelectLoop(ScheduledExecutorService executor) throws IOException {
        dontStop = true;
        selector = SelectorProvider.provider().openSelector();
        this.timeout = 0;
        this.executor = executor;
    }

    @Override
    public Future<?> submit(final Runnable runnable) {
        return submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                runnable.run();
                return null;
            }
        });
    }

  //  @Override
    public ScheduledFuture<?> schedule(final Runnable runnable,
                                       long delay, TimeUnit unit) {
        return schedule(
            new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    runnable.run();
                    return null;
                }
            }, delay, unit);
    }

    @Override
    public <V> Future<V> submit(final Callable<V> work) {
        return executor.submit(wrapWithLock(work));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(final Callable<V> work,
                                           long delay, TimeUnit unit) {
        return executor.schedule(wrapWithLock(work), delay, unit);
    }

    private <V> Callable<V> wrapWithLock(final Callable<V> work) {
        return new Callable<V>() {
            @Override
            public V call() throws Exception {
                synchronized (SelectLoop.this) {
                    return work.call();
                }
            }
        };
    }

    /**
     * Registers the supplied SelectableChannel with this SelectLoop.
     *
     * @param ch  the channel
     * @param ops interest ops
     * @param arg argument that will be returned with the SelectListener
     * @return SelectionKey
     * @throws ClosedChannelException if channel was already closed
     */
    public SelectionKey register(SelectableChannel ch, int ops,
                                 SelectListener arg)
        throws ClosedChannelException {
        synchronized (registerLock) {
            selector.wakeup();
            // The doLoop won't re-enter select() because we hold the
            // registerLock.  This is necessary because
            // SelectableChannel.register() contends with select() for
            // a lock, so it could block if we didn't prevent doLoop
            // from calling select() until after we call ch.register.
            // We won't block if wakeup() is called between the doLoop's
            // synchronizing on registerLock and its calling select(),
            // because select() returns immediately if a wakeup() was
            // called while the selector didn't have a select in progress.
            return ch.register(selector, ops, arg);
        }
    }

    /**
     * Unregisgters the supplied SelectableChannel with this SelectLoop.
     *
     * @param ch  the channel
     * @param ops interest ops
     * @param arg argument that will be returned with the SelectListener
     * @throws ClosedChannelException if channel was already closed
     */
    public void unregister(SelectableChannel ch, int ops)
        throws ClosedChannelException {
        synchronized (registerLock) {
            selector.wakeup();
            // The doLoop won't re-enter select() because we hold the
            // registerLock.  This is necessary because
            // SelectableChannel.register() contends with select() for
            // a lock, so it could block if we didn't prevent doLoop
            // from calling select() until after we call ch.register.
            // We won't block if wakeup() is called between the doLoop's
            // synchronizing on registerLock and its calling select(),
            // because select() returns immediately if a wakeup() was
            // called while the selector didn't have a select in progress.
            ch.register(selector, ops).cancel();
        }
    }

    /**
     * Main top-level IO loop this dispatches all IO events and timer events
     * together I believe this is fairly efficient
     */
    public void doLoop() throws IOException {
        log.debug("doLoop");

        int nEvents;

        while (dontStop) {
            log.debug("looping");

            synchronized (registerLock) {
            }
            nEvents = selector.select(timeout);
            log.debug("got {} events", nEvents);
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
        try {
            executor.shutdown();
            executor.awaitTermination(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            log.error("InterruptedException while shutting down the SelectLoop executor thread");
        }
    }

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
