/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.store.MockStoreProvider;
import com.midokura.midolman.monitoring.store.Store;

public class MockMonitoringStoreModule extends MonitoringStoreModule {

    private static final Logger log = LoggerFactory
            .getLogger(MockMonitoringStoreModule.class);

    @Override
    protected void bindCassandraStore() {
         // expose the mock cassandra stuff instead of the real one for the tests.
        bind(Store.class).toProvider(MockStoreProvider.class)
                .asEagerSingleton();
        expose(Store.class);
    }
}
