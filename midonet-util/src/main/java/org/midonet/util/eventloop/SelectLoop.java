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

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
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

import org.midonet.util.io.SelectorInputQueue;

public interface SelectLoop {
    public void setEndOfLoopCallback(Runnable cb);

    /**
     * Registers the supplied SelectableChannel with this SelectLoop.
     *
     * @param ch  the channel
     * @param ops interest ops
     * @param arg argument that will be returned with the SelectListener
     * @param priority priority which which the ops for the channel will
     *                 be processed if they are selected
     * @return SelectionKey
     * @throws ClosedChannelException if channel was already closed
     */
    public void register(SelectableChannel ch, int ops,
                         SelectListener arg, Priority priority)
        throws ClosedChannelException;

    /**
     * Removed the registration of the supplied SelectableChannel from this SelectLoop.
     *
     * @param ch  the channel
     * @param ops interest ops used to previously register the channel
     * @throws ClosedChannelException if channel was already closed
     */
    public void unregister(SelectableChannel ch, int ops)
        throws ClosedChannelException;

    /**
     * Registers the supplied SelectableChannel with this SelectLoop. The
     * interest ops will only be listened to when there's data queued in
     * the given BlockingQueue.
     *
     * @param queue the queue
     * @param ch    the channel
     * @param ops   interest ops
     * @param arg   argument that will be returned with the SelectListener
     * @param priority priority which which the ops for the channel will
     *                 be processed if they are selected
     */
    public void registerForInputQueue(SelectorInputQueue<?> queue,
                                      SelectableChannel ch,
                                      int ops, SelectListener arg,
                                      Priority priority);

    public void unregisterForInputQueue(SelectorInputQueue<?> queue);

    /**
     * Main top-level IO loop this dispatches all IO events and timer events
     * together I believe this is fairly efficient
     */
    public void doLoop() throws IOException;

    /**
     * Force this select loop to return immediately and re-enter select, useful
     * for example if a new item has been added to the select loop while it
     * was already blocked.
     */
    public void wakeup();

    /**
     * Shuts down this select loop, may return before it has fully shutdown
     */
    public void shutdown();

    /**
     * Order in which selectable channels will have their events processed if
     * they are selected. The order is defined by the order in which the enum
     * constants are declared.
     */
    public static enum Priority {
        HIGH, NORMAL
    }
}
