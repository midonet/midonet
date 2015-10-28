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
package org.midonet.netlink;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

/** Mocking version of NetlinkChannel. */
public class MockNetlinkChannel extends NetlinkChannel {

    public Queue<ByteBuffer> written = new LinkedList<>();
    public AtomicInteger packetsWritten = new AtomicInteger();
    public Queue<ByteBuffer> toRead = new LinkedList<>();

    public Netlink.Address address;

    public MockNetlinkChannel(SelectorProvider provider,
                              NetlinkProtocol protocol) {
        super(provider, protocol);
        setPid(0);
    }

    public void setPid(int pid) {
        address = new Netlink.Address(pid);
    }

    @Override
    protected void initSocket() { }

    @Override
    protected void _executeConnect(Netlink.Address address)
        throws IOException {
        state = ST_CONNECTED;
    }

    @Override
    protected void implConfigureBlocking(boolean block) throws IOException {
    }

    @Override
    public Netlink.Address getLocalAddress() {
        return address;
    }

    @Override
    protected void closeFileDescriptor() {
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        ByteBuffer src = toRead.poll();
        if (src == null) {
            return 0;
        }
        int nbytes = src.remaining();
        dst.put(src);
        return nbytes;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        written.add(src);
        packetsWritten.incrementAndGet();
        return src.remaining();
    }

    @Override
    protected void implCloseSelectableChannel() {

    }
}
