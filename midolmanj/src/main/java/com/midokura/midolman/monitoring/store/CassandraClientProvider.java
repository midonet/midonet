/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.monitoring.store;

import com.google.inject.Inject;
import com.google.inject.Provider;

import com.midokura.cassandra.CassandraClient;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;

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
