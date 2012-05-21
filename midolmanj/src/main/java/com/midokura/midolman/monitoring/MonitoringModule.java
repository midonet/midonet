/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import javax.annotation.Nullable;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import me.prettyprint.hector.api.exceptions.HectorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.config.ConfigProvider;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.store.CassandraStore;
import com.midokura.midolman.monitoring.store.Store;

/**
 * Date: 4/25/12
 */
public class MonitoringModule extends AbstractModule {

    private final static Logger log =
        LoggerFactory.getLogger(MonitoringModule.class);

    private ConfigProvider configProvider;
    private HostIdProvider provider;

    public MonitoringModule(ConfigProvider configProvider,
                            HostIdProvider hostIdProvider) {
        this.configProvider = configProvider;
        this.provider = hostIdProvider;
    }

    @Override
    protected void configure() {

        bind(MonitoringConfiguration.class)
            .toInstance(configProvider.getConfig(MonitoringConfiguration.class));

        bind(HostIdProvider.class).toInstance(provider);
    }

    @Singleton
    @Provides
    @Nullable
    public Store getStore(MonitoringConfiguration config) {
        Store store = null;
        try {
            store = new CassandraStore(
                config.getCassandraServers(),
                config.getCassandraCluster(),
                config.getMonitoringCassandraKeyspace(),
                config.getMonitoringCassandraColumnFamily(),
                config.getMonitoringCassandraReplicationFactor(),
                config.getMonitoringCassandraExpirationTimeout());
        } catch (HectorException e) {
            log.error("Fatal error, unable to initialize CassandraStore", e);
        }
        return store;
    }
}
