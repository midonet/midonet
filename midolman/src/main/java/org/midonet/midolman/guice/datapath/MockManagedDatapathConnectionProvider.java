/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import javax.inject.Inject;

import com.google.inject.Provider;
import org.midonet.midolman.io.ManagedDatapathConnection;
import org.midonet.midolman.io.MockManagedDatapathConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.Reactor;


/**
 * Will provide an {@link OvsDatapathConnection} instance that is handled via
 * by an in memory datapath store.
 */
public class MockManagedDatapathConnectionProvider implements
                                           Provider<ManagedDatapathConnection> {

    private static final Logger log =
        LoggerFactory.getLogger(MockManagedDatapathConnectionProvider.class);

    @Inject
    Reactor reactor;

    @Override
    public ManagedDatapathConnection get() {
        try {
            return new MockManagedDatapathConnection(reactor);
        } catch (Exception e) {
            log.error("Error creating the Mock OvsDatapath connection");
            throw new RuntimeException(e);
        }
    }

}
