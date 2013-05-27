/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import java.nio.channels.spi.SelectorProvider;
import javax.inject.Inject;

import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkChannel;
import org.midonet.netlink.NetlinkSelectorProvider;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.throttling.ThrottlingGuardFactory;


/**
 * This will create a OvsDatapathConnection which is already connected to the
 * local netlink kernel module.
 */
public class OvsDatapathConnectionProvider implements
                                           Provider<OvsDatapathConnection> {

    private static final Logger log = LoggerFactory
        .getLogger(OvsDatapathConnectionProvider.class);

    @Inject
    Reactor reactor;

    @Inject
    @DatapathModule.DATAPATH_THROTTLING_GUARD
    ThrottlingGuardFactory tgFactory;

    @Inject
    @DatapathModule.NETLINK_SEND_BUFFER_POOL
    BufferPool netlinkSendPool;

    @Override
    public OvsDatapathConnection get() {
        try {
            SelectorProvider provider = SelectorProvider.provider();

            if (!(provider instanceof NetlinkSelectorProvider)) {
                log.error("Invalid selector type: {}", provider.getClass());
                throw new RuntimeException();
            }

            NetlinkSelectorProvider netlinkSelector = (NetlinkSelectorProvider) provider;

            final NetlinkChannel netlinkChannel =
                netlinkSelector.openNetlinkSocketChannel(Netlink.Protocol.NETLINK_GENERIC);

            log.info("Connecting");
            netlinkChannel.connect(new Netlink.Address(0));

            return OvsDatapathConnection.create(
                    netlinkChannel, reactor, tgFactory, netlinkSendPool);
        } catch (Exception e) {
            log.error("Error connecting to the netlink socket");
            throw new RuntimeException(e);
        }
    }
}
