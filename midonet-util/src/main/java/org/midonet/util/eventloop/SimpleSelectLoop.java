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
package org.midonet.util.eventloop;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.util.io.SelectorInputQueue;

/**
 * Dirt simple SelectLoop for simple java controller
 * <p/>
 * Originally from org.openflowj.examples
 */
public class SimpleSelectLoop implements SelectLoop {

    private static final Logger log = LoggerFactory.getLogger(SelectLoop.class);

    protected boolean dontStop;
    protected Selector selector;
    // The timeout value in milliseconds that select will be called with
    // (currently zero, may be settable in the future).
    protected long timeout;
    protected final Object registerLock = new Object();

    private Multimap<SelectableChannel, Registration> registrations =
        HashMultimap.create();

    public SimpleSelectLoop() throws IOException {
        dontStop = true;
        selector = SelectorProvider.provider().openSelector();
        this.timeout = 0;
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
    public void register(SelectableChannel ch, int ops,
                                 SelectListener arg)
        throws ClosedChannelException {
        if (ch == null)
            throw new RuntimeException("can't register interest in a null channel");
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
            registrations.put(ch, new Registration(ch, ops, arg, null));
        }
    }

    /**
     * Removed the registration of the supplied SelectableChannel from this SelectLoop.
     *
     * @param ch  the channel
     * @param ops interest ops used to previously register the channel
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
            for (Registration reg: registrations.get(ch)) {
                if (reg.ops == ops) {
                    registrations.remove(ch, reg);
                    break;
                }
            }
        }
    }

    /**
     * Registers the supplied SelectableChannel with this SelectLoop. The
     * interest ops will only be listened to when there's data queued in
     * the given SelectorInputQueue.
     *
     * @param queue the queue
     * @param ch    the channel
     * @param ops   interest ops
     * @param arg   argument that will be returned with the SelectListener
     */
    public void registerForInputQueue(SelectorInputQueue<?> queue,
                                      SelectableChannel ch,
                                      int ops,
                                      SelectListener arg) {
        log.debug("Registering channel bound to input queue");
        if (ch == null)
            throw new RuntimeException("can't register interest in a null channel");
        synchronized (registerLock) {
            selector.wakeup();
            queue.bind(selector);
            registrations.put(ch, new Registration(ch, ops, arg, queue));
        }
    }

    public void unregisterForInputQueue(SelectorInputQueue<?> queue) {
        synchronized (registerLock) {
            selector.wakeup();
            queue.unbind();
            for (Registration reg: registrations.values()) {
                if (reg.queue == queue) {
                    registrations.remove(reg.ch, reg);
                    break;
                }
            }
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
            log.trace("looping");

            synchronized (registerLock) {
                for (SelectableChannel ch: registrations.keySet()) {
                    int ops = 0;
                    for (Registration reg: registrations.get(ch)) {
                        if (reg.queue != null) {
                            reg.queue.wakeupOn();
                            if (!reg.queue.isEmpty()) {
                                reg.queue.wakeupOff();
                                ops |= reg.ops;
                            }
                        } else {
                            ops |= reg.ops;
                        }
                    }
                    if (ops != 0)
                        ch.register(selector, ops);
                    else
                        ch.register(selector, ch.validOps()).cancel();
                }
            }

            nEvents = selector.select(timeout);
            log.trace("got {} events", nEvents);

            synchronized (registerLock) {
                for (SelectableChannel ch: registrations.keySet()) {
                    for (Registration reg: registrations.get(ch)) {
                        if (reg.queue != null)
                            reg.queue.wakeupOff();
                    }
                }
            }

            if (nEvents > 0) {
                for (SelectionKey sk : selector.selectedKeys()) {
                    if (!sk.isValid())
                        continue;
                    int ops = sk.readyOps();
                    SelectableChannel ch = sk.channel();
                    for (Registration reg: registrations.get(ch)) {
                        if (ops == 0) {
                            break;
                        } else if ((reg.ops & ops) != 0) {
                            try {
                                reg.listener.handleEvent(sk);
                            } catch (Exception e) {
                                log.error("Callback threw an exception", e);
                            }
                            // We report each ready-op once, so after
                            // dispatching the event we clear it from ops.
                            ops &= ~reg.ops;
                        }
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

    private class Registration {
        final SelectableChannel ch;
        final int ops;
        final SelectListener listener;
        final SelectorInputQueue<?> queue;

        Registration(SelectableChannel ch, int ops, SelectListener arg,
                     SelectorInputQueue<?> queue) {
            this.ch = ch;
            this.ops = ops;
            this.listener = arg;
            this.queue = queue;
        }
    }
}
