/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import com.google.inject.PrivateModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import me.prettyprint.hector.api.exceptions.HectorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.config.ConfigProvider;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.guice.MonitoringConfigurationProvider;
import com.midokura.midolman.monitoring.metrics.VMMetricsCollection;
import com.midokura.midolman.monitoring.metrics.ZookeeperMetricsCollection;
import com.midokura.midolman.monitoring.store.CassandraStore;
import com.midokura.midolman.monitoring.store.Store;
import com.midokura.midolman.services.HostIdProviderService;

/**
 * Date: 4/25/12
 */
public class MonitoringModule extends PrivateModule {

    private final static Logger log =
        LoggerFactory.getLogger(MonitoringModule.class);

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(HostIdProviderService.class);
        requireBinding(ConfigProvider.class);

        bind(VMMetricsCollection.class);
        bind(ZookeeperMetricsCollection.class);
        bind(HostKeyService.class);

        bind(MonitoringAgent.class);
        expose(MonitoringAgent.class);

        bind(MonitoringConfiguration.class)
            .toProvider(MonitoringConfigurationProvider.class)
            .in(Singleton.class);
        expose(MonitoringConfiguration.class);
    }

    @Singleton
    @Provides
    public Store getStore(MonitoringConfiguration config) {
        try {
            return new CassandraStore(
                config.getCassandraServers(),
                config.getCassandraCluster(),
                config.getMonitoringCassandraKeyspace(),
                config.getMonitoringCassandraColumnFamily(),
                config.getMonitoringCassandraReplicationFactor(),
                config.getMonitoringCassandraExpirationTimeout());
        } catch (HectorException e) {
            log.error("Fatal error, unable to initialize CassandraStore", e);
            throw new RuntimeException(e);
        }
    }
}
