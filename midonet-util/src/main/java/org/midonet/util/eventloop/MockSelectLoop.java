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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;


import org.midonet.util.io.SelectorInputQueue;

public class MockSelectLoop implements SelectLoop {

    protected boolean dontStop;

    public MockSelectLoop() throws IOException {}

    public void setEndOfLoopCallback(Runnable cb) {}

    public void register(SelectableChannel ch, int ops, SelectListener arg,
                         Priority priority)
            throws ClosedChannelException {}

    public void unregister(SelectableChannel ch, int ops)
        throws ClosedChannelException {}

    public void registerForInputQueue(SelectorInputQueue<?> queue,
                                      SelectableChannel ch,
                                      int ops,
                                      SelectListener arg,
                                      Priority priority) {}

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
