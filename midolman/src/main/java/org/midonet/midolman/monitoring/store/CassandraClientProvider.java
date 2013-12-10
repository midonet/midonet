/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.monitoring.store;

import com.google.inject.Inject;
import com.google.inject.Provider;

import org.midonet.cassandra.CassandraClient;
import org.midonet.midolman.guice.CacheModule;
import org.midonet.midolman.monitoring.config.MonitoringConfiguration;
import org.midonet.util.eventloop.Reactor;

/**
 * Providers CassandraClient
 */
public class CassandraClientProvider implements Provider<CassandraClient> {

    private final MonitoringConfiguration config;

    @Inject @CacheModule.CACHE_REACTOR
    Reactor reactor;

    @Inject
    public CassandraClientProvider(MonitoringConfiguration config) {
        this.config = config;
    }

    @Override
    public CassandraClient get() {
        return new CassandraClient(
                config.getCassandraServers(),
                config.getCassandraMaxActiveConnections(),
                config.getCassandraCluster(),
                config.getMonitoringCassandraKeyspace(),
                config.getMonitoringCassandraColumnFamily(),
                config.getCassandraReplicationFactor(),
                config.getMonitoringCassandraExpirationTimeout(),
                config.getCassandraThriftSocketTimeout(),
                config.getCassandraHostTimeoutTracker(),
                config.getCassandraHostTimeoutCounter(),
                config.getCassandraHostTimeoutWindow(),
                reactor
        );
    }
}
