// Copyright 2013 Midokura Inc.

package org.midonet.util.io;

import java.nio.channels.Selector;
import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A BlockingQueue specialization that can be bound to a Selector. When bound,
 * the selector will be woken up whenever data is put in the queue. It is meant
 * to be combined with a selector used to write data to a channel as it arrives
 * to this queue.
 */
public class SelectorInputQueue<E> extends LinkedBlockingQueue<E> {
    private Selector selector = null;

    public void bind(Selector s) {
        this.selector = s;
    }

    public void unbind() {
        this.selector = null;
    }

    @Override
    public boolean add(E e) {
        if (super.add(e)) {
            if (selector != null)
                selector.wakeup();
            return true;
        }
        return false;
    }

    @Override
    public boolean offer(E e) {
        if (super.offer(e)) {
            if (selector != null)
                selector.wakeup();
            return true;
        }
        return false;
    }

    @Override
    public void put(E e) throws InterruptedException {
        super.put(e);
        if (selector != null)
            selector.wakeup();
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (super.offer(e, timeout, unit)) {
            if (selector != null)
                selector.wakeup();
            return true;
        }
        return false;
    }

    @Override
    public boolean addAll(Collection c) {
        if (super.addAll(c)) {
            if (selector != null)
                selector.wakeup();
            return true;
        }
        return false;
    }
}
