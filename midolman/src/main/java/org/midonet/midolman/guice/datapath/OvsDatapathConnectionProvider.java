/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import javax.inject.Inject;

import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Netlink;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.throttling.ThrottlingGuard;
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
    @DatapathModule.SIMULATION_THROTTLING_GUARD
    ThrottlingGuard simulationThrottler;

    @Inject
    @DatapathModule.NETLINK_SEND_BUFFER_POOL
    BufferPool netlinkSendPool;

    @Override
    public OvsDatapathConnection get() {
        try {
            return OvsDatapathConnection.create(new Netlink.Address(0),
                reactor, simulationThrottler, netlinkSendPool);
        } catch (Exception e) {
            log.error("Error creating OvsDatapathConnection");
            throw new RuntimeException(e);
        }
    }
}
