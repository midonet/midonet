/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import javax.inject.Inject;

import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.Reactor;


/**
 * Will provide an {@link OvsDatapathConnection} instance that is handled via
 * by an in memory datapath store.
 */
public class MockOvsDatapathConnectionProvider implements
                                           Provider<OvsDatapathConnection> {

    private static final Logger log =
        LoggerFactory.getLogger(MockOvsDatapathConnectionProvider.class);

    @Inject
    Reactor reactor;

    @Override
    public OvsDatapathConnection get() {
        try {
            return OvsDatapathConnection.createMock(reactor);
        } catch (Exception e) {
            log.error("Error creating the Mock OvsDatapath connection");
            throw new RuntimeException(e);
        }
    }

}
