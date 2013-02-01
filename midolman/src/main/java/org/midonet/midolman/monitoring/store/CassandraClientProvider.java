/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.monitoring.store;

import com.google.inject.Inject;
import com.google.inject.Provider;

import org.midonet.cassandra.CassandraClient;
import org.midonet.midolman.monitoring.config.MonitoringConfiguration;

/**
 * Providers CassandraClient
 */
public class CassandraClientProvider implements Provider<CassandraClient> {

    private final MonitoringConfiguration config;

    @Inject
    public CassandraClientProvider(MonitoringConfiguration config) {
        this.config = config;
    }

    @Override
    public CassandraClient get() {
        return new CassandraClient(
                config.getCassandraServers(),
                config.getCassandraCluster(),
                config.getMonitoringCassandraKeyspace(),
                config.getMonitoringCassandraColumnFamily(),
                config.getCassandraReplicationFactor(),
                config.getMonitoringCassandraExpirationTimeout()
        );
    }
}
