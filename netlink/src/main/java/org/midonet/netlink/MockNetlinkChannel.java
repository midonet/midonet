/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.netlink;

import java.io.IOException;
import java.nio.channels.spi.SelectorProvider;

/** package private mocking version of NetlinkChannelImpl for NetlinkChannel. */
class MockNetlinkChannel extends NetlinkChannel {

    public MockNetlinkChannel(
            SelectorProvider provider, Netlink.Protocol protocol) {
        super(provider, protocol);
    }

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
