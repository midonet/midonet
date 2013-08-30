/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.monitoring.store;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.midonet.cassandra.CassandraClient;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.guice.CacheModule;
import org.midonet.midolman.monitoring.config.MonitoringConfiguration;
import org.midonet.util.eventloop.Reactor;

/**
 * Providers CassandraStore
 */
public class CassandraStoreProvider implements Provider<CassandraStore> {

    @Inject
    ConfigProvider configProvider;

    @Inject @CacheModule.CACHE_REACTOR
    Reactor reactor;

    @Override
    public CassandraStore get() {
        MonitoringConfiguration config = configProvider.getConfig(MonitoringConfiguration.class);
        CassandraClient client = new CassandraClient(
            config.getCassandraServers(),
            config.getCassandraMaxActiveConnections(),
            config.getCassandraCluster(),
            config.getMonitoringCassandraKeyspace(),
            config.getMonitoringCassandraColumnFamily(),
            config.getCassandraReplicationFactor(),
            config.getMonitoringCassandraExpirationTimeout(),
            reactor);
        return new CassandraStore(client);
    }
}
