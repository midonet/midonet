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
    protected Object registrationLock;
    protected int registrationRequests = 0;
    protected Queue<Object[]> registrationQueue;
    protected Selector selector;
    protected long timeout;
    
    protected ScheduledExecutorService executor;

    public SelectLoop(ScheduledExecutorService executor) throws IOException {
        this(0);
        
        this.executor = executor;
    }

    /**
     * Initializes this SelectLoop
     * @param cb the callback to call when select returns
     * @param timeout the timeout value in milliseconds that select will be
     *        called with
     * @throws IOException
     */
    public SelectLoop(long timeout) throws IOException {
        dontStop = true;
        selector = SelectorProvider.provider().openSelector();
        registrationLock = new Object();
        registrationQueue = new ConcurrentLinkedQueue<Object[]>();
        this.timeout = timeout;
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
    public ScheduledFuture schedule(final Runnable runnable, long delay, TimeUnit unit) {
        return executor.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (SelectLoop.this) {
                    runnable.run();
                }
            }
        }, delay, unit);
    }

    public void register(SelectableChannel ch, int ops, Object arg)
            throws ClosedChannelException {
        registrationQueue.add(new Object[] {ch, ops, arg});
    }

    /**
     * Registers the supplied SelectableChannel with this SelectLoop. Note this
     * method blocks until registration proceeds.  It is advised that
     * SelectLoop is intialized with a timeout value when using this method.
     * @param ch the channel
     * @param ops interest ops
     * @param arg argument that will be returned with the SelectListener
     * @return SelectionKey
     * @throws ClosedChannelException if channel was already closed
     */
    public synchronized SelectionKey registerBlocking(SelectableChannel ch, int ops, SelectListener arg)
            throws ClosedChannelException {
        synchronized (registrationLock) {
            registrationRequests++;
        }
        selector.wakeup();
        SelectionKey key = ch.register(selector, ops, arg);
        synchronized (registrationLock) {
            registrationRequests--;
            registrationLock.notifyAll();
        }
        return key;
    }

    /**
     * Main top-level IO loop this dispatches all IO events and timer events
     * together I believe this is fairly efficient
     **/
    public void doLoop() throws IOException {
        log.debug("doLoop");
        
        int nEvents;
        processRegistrationQueue();

        while (dontStop) {
            log.debug("looping");
          
            nEvents = selector.select(timeout);
            if (nEvents > 0) {
                for (Iterator<SelectionKey> i = selector.selectedKeys().iterator(); i.hasNext();) {
                    SelectionKey sk = i.next();
                    i.remove();

                    if (!sk.isValid())
                        continue;

                    SelectListener callback = (SelectListener) sk.attachment();
                    synchronized (this) {
                        callback.handleEvent(sk);
                    }
                }
            }

            if (this.registrationQueue.size() > 0)
                processRegistrationQueue();

            if (registrationRequests > 0) {
                synchronized (registrationLock) {
                    while (registrationRequests > 0) {
                        try {
                            registrationLock.wait();
                        } catch (InterruptedException e) {
                            log.warn("doLoop", e);
                        }
                    }
                }
            }
        }
    }

    protected void processRegistrationQueue() {
        // add any elements in queue
        for (Iterator<Object[]> it = registrationQueue.iterator(); it.hasNext();) {
            Object[] args = it.next();
            SelectableChannel ch = (SelectableChannel) args[0];
            try {
                ch.register(selector, (Integer) args[1], args[2]);
            } catch (ClosedChannelException e) {
                log.warn("processRegistrationQueue", e);
            }
            it.remove();
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
