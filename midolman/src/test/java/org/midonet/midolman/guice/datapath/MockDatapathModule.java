/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import javax.inject.Singleton;

import com.google.inject.Inject;
import com.google.inject.Provider;

import org.midonet.midolman.io.DatapathConnectionPool;
import org.midonet.midolman.io.MockDatapathConnectionPool;
import org.midonet.midolman.io.UpcallDatapathConnectionManager;
import org.midonet.midolman.util.mock.MockUpcallDatapathConnectionManager;


public class MockDatapathModule extends DatapathModule {
    @Override
    protected void bindUpcallDatapathConnectionManager() {
        bind(UpcallDatapathConnectionManager.class)
                .toProvider(MockUpcallDatapathConnectionManagerProvider.class)
                .in(Singleton.class);
    }

    @Override
    protected void bindDatapathConnectionPool() {
        bind(DatapathConnectionPool.class).
            toInstance(new MockDatapathConnectionPool());
    }

    public static class MockUpcallDatapathConnectionManagerProvider
            implements Provider<UpcallDatapathConnectionManager> {

        @Inject
        org.midonet.midolman.config.MidolmanConfig config;

        @Override
        public UpcallDatapathConnectionManager get() {
            return new MockUpcallDatapathConnectionManager(config);

        }
    }
}
