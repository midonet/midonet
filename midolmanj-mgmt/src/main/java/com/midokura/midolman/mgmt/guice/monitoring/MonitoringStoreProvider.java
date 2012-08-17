/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.monitoring;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.store.CassandraStore;
import com.midokura.midolman.monitoring.store.Store;

/**
 * Provider for MonitoringStore
 */
public class MonitoringStoreProvider implements Provider<Store> {

    private final MonitoringConfiguration config;

    @Inject
    public MonitoringStoreProvider(MonitoringConfiguration config) {
        this.config = config;
    }

    @Override
    public Store get() {

        return new CassandraStore(config.getCassandraServers(),
                config.getCassandraCluster(),
                config.getMonitoringCassandraKeyspace(),
                config.getMonitoringCassandraColumnFamily(),
                config.getMonitoringCassandraReplicationFactor(),
                config.getMonitoringCassandraExpirationTimeout());
    }

}
