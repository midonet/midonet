/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.cluster;

import com.midokura.cassandra.CassandraClient;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.guice.MonitoringConfigurationProvider;
import com.midokura.midolman.monitoring.store.CassandraClientProvider;
import com.midokura.midolman.monitoring.store.MockCassandraStoreProvider;
import com.midokura.midolman.monitoring.store.Store;
import com.midokura.midolman.state.Directory;
import com.midokura.midonet.cluster.Client;
import com.midokura.midonet.cluster.LocalClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class defines dependency bindings for DataClient and Client
 * interfaces.  It extends DataClusterClientModule that defines bindings
 * for DataClient, and it defines the bindings specific to Client.
 */
public class MockClusterClientModule extends DataClusterClientModule {

    private static final Logger log = LoggerFactory
            .getLogger(MockClusterClientModule.class);

    @Override
    protected void configure() {
        super.configure();

        requireBinding(Directory.class);

        bind(Client.class)
                .to(LocalClientImpl.class)
                .asEagerSingleton();
        expose(Client.class);
    }

    @Override
    protected void bindCassandraStore() {
        requireBinding(MonitoringConfiguration.class);
        bind(MonitoringConfiguration.class).toProvider(
                MonitoringConfigurationProvider.class).asEagerSingleton();

        bind(CassandraClient.class).toProvider(CassandraClientProvider.class)
                .asEagerSingleton();

         // expose the mock cassandra stuff instead of the real one for the tests.
        bind(Store.class).toProvider(MockCassandraStoreProvider.class)
                .asEagerSingleton();
        expose(Store.class);
        expose(MonitoringConfiguration.class);
    }
}
