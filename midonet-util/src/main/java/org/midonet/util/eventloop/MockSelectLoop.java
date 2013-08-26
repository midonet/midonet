/*
 * Copyright 2013 Midokura Europe SARL
 */
package org.midonet.util.eventloop;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;


import org.midonet.util.io.SelectorInputQueue;

public class MockSelectLoop implements SelectLoop {

    protected boolean dontStop;

    public MockSelectLoop() throws IOException {}

    public void register(SelectableChannel ch, int ops, SelectListener arg)
            throws ClosedChannelException {}

    public void unregister(SelectableChannel ch, int ops)
        throws ClosedChannelException {}

    public void registerForInputQueue(SelectorInputQueue<?> queue,
                                      SelectableChannel ch,
                                      int ops,
                                      SelectListener arg) {}

    public void unregisterForInputQueue(SelectorInputQueue<?> queue) {}

    public void doLoop() throws IOException {
        while (dontStop) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {}
        }
    }

    public void wakeup() {}

    /**
     * Shuts down this select loop, may return before it has fully shutdown
     */
    public void shutdown() {
        this.dontStop = false;
    }
}
