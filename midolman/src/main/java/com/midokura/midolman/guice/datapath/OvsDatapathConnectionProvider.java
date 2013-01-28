/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.datapath;

import java.nio.channels.spi.SelectorProvider;
import javax.inject.Inject;

import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.netlink.Netlink;
import com.midokura.netlink.NetlinkChannel;
import com.midokura.netlink.NetlinkSelectorProvider;
import com.midokura.odp.protos.OvsDatapathConnection;
import com.midokura.util.eventloop.Reactor;


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

            return OvsDatapathConnection.create(netlinkChannel, reactor);
        } catch (Exception e) {
            log.error("Error connecting to the netlink socket");
            throw new RuntimeException(e);
        }
    }
}
