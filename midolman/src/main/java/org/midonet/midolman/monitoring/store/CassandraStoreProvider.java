/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.monitoring.store;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.midonet.cassandra.CassandraClient;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.monitoring.config.MonitoringConfiguration;

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
            config.getCassandraMaxActiveConnections(),
            config.getCassandraCluster(),
            config.getMonitoringCassandraKeyspace(),
            config.getMonitoringCassandraColumnFamily(),
            config.getCassandraReplicationFactor(),
            config.getMonitoringCassandraExpirationTimeout()
        );
        return new CassandraStore(client);
    }
}
