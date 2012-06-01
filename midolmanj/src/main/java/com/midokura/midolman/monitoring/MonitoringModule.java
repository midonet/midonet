/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import me.prettyprint.hector.api.exceptions.HectorException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.config.DefaultMonitoringConfiguration;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.store.CassandraStore;
import com.midokura.midolman.monitoring.store.Store;

/**
 * Date: 4/25/12
 */
public class MonitoringModule extends AbstractModule {

    private final static Logger log =
            LoggerFactory.getLogger(MonitoringModule.class);

    private HierarchicalConfiguration configuration;

    public MonitoringModule(HierarchicalConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    protected void configure() {
        bind(MonitoringConfiguration.class)
            .to(DefaultMonitoringConfiguration.class)
            .asEagerSingleton();

        bind(HierarchicalConfiguration.class)
            .annotatedWith(
                Names.named(DefaultMonitoringConfiguration.MONITORING_CONFIG))
            .toInstance(configuration);
    }

    @Singleton
    @Provides
    public Store getStore(MonitoringConfiguration config) {
        Store store = null;
        try {
            store = new CassandraStore(
                config.getCassandraServers(),
                config.getCassandraCluster(),
                config.getMonitoringCassandraKeySpace(),
                config.getMonitoringCassandraColumnFamily(),
                config.getCassandraReplicationFactor(),
                config.getCassandraExpirationTimeout());
        } catch (HectorException e) {
            log.error("Fatal error, enable to initialize CassandraStore", e);
        }
        return store;
    }
}
