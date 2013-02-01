/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import javax.inject.Singleton;

import org.midonet.odp.protos.OvsDatapathConnection;


public class MockDatapathModule extends DatapathModule {
    @Override
    protected void bindOvsDatapathConnection() {
        bind(OvsDatapathConnection.class)
            .toProvider(MockOvsDatapathConnectionProvider.class)
            .in(Singleton.class);
    }
}
