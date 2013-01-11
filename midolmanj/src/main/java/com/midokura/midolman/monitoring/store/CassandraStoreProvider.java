/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.monitoring.store;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.cassandra.CassandraClient;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;

/**
 * Providers CassandraStore
 */
public class CassandraStoreProvider implements Provider<CassandraStore> {

    @Inject
    ConfigProvider configProvider;

    @Override
    public CassandraStore get() {
        MonitoringConfiguration config = configProvider.getConfig(MonitoringConfiguration.class);
        CassandraClient client = new CassandraClient(
            config.getCassandraServers(),
            config.getCassandraCluster(),
            config.getMonitoringCassandraKeyspace(),
            config.getMonitoringCassandraColumnFamily(),
            config.getCassandraReplicationFactor(),
            config.getMonitoringCassandraExpirationTimeout()
        );
        return new CassandraStore(client);
    }
}
