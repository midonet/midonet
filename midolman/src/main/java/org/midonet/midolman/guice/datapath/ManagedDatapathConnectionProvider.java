/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import javax.inject.Inject;

import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.io.DualSelectorDatapathConnection;
import org.midonet.midolman.io.ManagedDatapathConnection;
import org.midonet.util.eventloop.Reactor;

/**
 * This will create a OvsDatapathConnection which is already connected to the
 * local netlink kernel module.
 */
public class ManagedDatapathConnectionProvider implements
                                           Provider<ManagedDatapathConnection> {

    private static final Logger log = LoggerFactory
        .getLogger(ManagedDatapathConnectionProvider.class);

    @Inject
    Reactor reactor;

    @Inject
    MidolmanConfig config;

    @Override
    public ManagedDatapathConnection get() {
        try {
            return new DualSelectorDatapathConnection(
                "datapath", reactor, config, true);
        } catch (Exception e) {
            log.error("Error creating OvsDatapathConnection");
            throw new RuntimeException(e);
        }
    }
}
