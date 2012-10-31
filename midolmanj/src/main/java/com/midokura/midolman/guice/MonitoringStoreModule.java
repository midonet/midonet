/*
* Copyright 2012 Midokura PTE LTD.
*/
package com.midokura.midolman.guice;

import com.google.inject.PrivateModule;

import com.midokura.cassandra.CassandraClient;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.guice.MonitoringConfigurationProvider;
import com.midokura.midolman.monitoring.store.CassandraClientProvider;
import com.midokura.midolman.monitoring.store.CassandraStoreProvider;
import com.midokura.midolman.monitoring.store.Store;

/**
 * Data cluster client module.  This class defines dependency bindings
 * for simple data access via DataClient interface.
 */
public class MonitoringStoreModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        bind(MonitoringConfiguration.class).toProvider(
            MonitoringConfigurationProvider.class).asEagerSingleton();
        expose(MonitoringConfiguration.class);

        bindCassandraStore();
    }

    protected void bindCassandraStore() {
        bind(CassandraClient.class).toProvider(CassandraClientProvider.class)
                .asEagerSingleton();
        bind(Store.class).toProvider(CassandraStoreProvider.class)
                .asEagerSingleton();
        expose(Store.class);
    }


}
