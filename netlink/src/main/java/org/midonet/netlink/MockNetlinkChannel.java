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
import java.nio.channels.spi.SelectorProvider;

/** Mocking version of NetlinkChannel. */
public class MockNetlinkChannel extends NetlinkChannel {

    public MockNetlinkChannel(SelectorProvider provider,
                              NetlinkProtocol protocol) {
        super(provider, protocol);
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
        return new Netlink.Address(0);
    }

    @Override
    protected void closeFileDescriptor() {
    }

}
