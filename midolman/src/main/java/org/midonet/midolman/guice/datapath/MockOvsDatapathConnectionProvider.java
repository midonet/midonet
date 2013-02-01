/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import java.io.IOException;
import java.nio.channels.spi.SelectorProvider;
import javax.inject.Inject;

import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkChannelImpl;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.odp.protos.mocks.MockOvsDatapathConnectionImpl;
import org.midonet.util.eventloop.Reactor;


/**
 * Will provide an {@link OvsDatapathConnection} instance that is handled via
 * by an in memory datapath store.
 */
public class MockOvsDatapathConnectionProvider implements
                                           Provider<OvsDatapathConnection> {

    private static final Logger log = LoggerFactory
        .getLogger(MockOvsDatapathConnectionProvider.class);

    @Inject
    Reactor reactor;

    @Override
    public OvsDatapathConnection get() {
        try {
            SelectorProvider provider = SelectorProvider.provider();

            return new MockOvsDatapathConnectionImpl(
                new MockNetlinkChannel(provider, Netlink.Protocol.NETLINK_GENERIC), reactor);
        } catch (Exception e) {
            log.error("Error connecting to the netlink socket");
            throw new RuntimeException(e);
        }
    }

    private static class MockNetlinkChannel extends NetlinkChannelImpl {

        public MockNetlinkChannel(SelectorProvider provider, Netlink.Protocol protocol) {
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
    }
}
