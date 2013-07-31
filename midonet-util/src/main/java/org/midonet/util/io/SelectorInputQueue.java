// Copyright 2013 Midokura Inc.

package org.midonet.util.io;

import java.nio.channels.Selector;
import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A BlockingQueue specialization that can be bound to a Selector. When bound,
 * the selector will be woken up whenever data is put in the queue. It is meant
 * to be combined with a selector used to write data to a channel as it arrives
 * to this queue.
 */
@SuppressWarnings("serial") // unless someones wants to serialize this ...
public class SelectorInputQueue<E> extends LinkedBlockingQueue<E> {
    private Selector selector = null;

    private AtomicBoolean wakeupSubscription = new AtomicBoolean(true);

    /**
     * Registers the interest of the selector bound to this queue in being
     * awakened when a new element is placed in the queue. To avoid losing
     * events, the interest should be switched on before checking the queue
     * for elements and, if found empty, until after the call to select()
     * returns. Example:
     *
     * while (true) {
     *     queue.wakeupOn();
     *     if (!queue.isEmpty()) {
     *         queue.wakeupOff();
     *         // add write to the set of interest ops for the subsequent
     *         // select() call
     *     }
     *     selector.select();
     *     queue.wakeupOff();
     *     // handle select() events, if any
     * }
     *
     * The queue will wake up the selector just once, until the registration
     * is switched back on.
     */
    public void wakeupOn() {
        wakeupSubscription.set(true);
    }

    /**
     * See wakeupOn()
     */
    public void wakeupOff() {
        wakeupSubscription.set(false);
    }

    /**
     * Binds this queue to a Selector that may be awakened when new elements
     * are placed in the queue.
     */
    public void bind(Selector s) {
        this.selector = s;
    }

    /**
     * Unbinds this queue from the previously bound selector.
     */
    public void unbind() {
        this.selector = null;
    }

    private void notifySelector() {
        if (selector != null && wakeupSubscription.compareAndSet(true, false))
            selector.wakeup();
    }

    @Override
    public boolean add(E e) {
        if (super.add(e)) {
            notifySelector();
            return true;
        }
        return false;
    }

    @Override
    public boolean offer(E e) {
        if (super.offer(e)) {
            notifySelector();
            return true;
        }
        return false;
    }

    @Override
    public void put(E e) throws InterruptedException {
        super.put(e);
        notifySelector();
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (super.offer(e, timeout, unit)) {
            notifySelector();
            return true;
        }
        return false;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        if (super.addAll(c)) {
            notifySelector();
            return true;
        }
        return false;
    }
}
