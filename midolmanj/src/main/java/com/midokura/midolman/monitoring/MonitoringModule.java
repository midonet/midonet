/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import com.google.inject.PrivateModule;
import com.google.inject.Singleton;
import com.midokura.cassandra.CassandraClient;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.guice.MonitoringConfigurationProvider;
import com.midokura.midolman.monitoring.metrics.VMMetricsCollection;
import com.midokura.midolman.monitoring.metrics.ZookeeperMetricsCollection;
import com.midokura.midolman.monitoring.store.CassandraClientProvider;
import com.midokura.midolman.monitoring.store.CassandraStoreProvider;
import com.midokura.midolman.monitoring.store.Store;
import com.midokura.midolman.services.HostIdProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        requireBinding(MonitoringConfiguration.class);
        requireBinding(Store.class);

        bind(VMMetricsCollection.class);
        bind(ZookeeperMetricsCollection.class);
        bind(HostKeyService.class);

        bind(MonitoringAgent.class);
        expose(MonitoringAgent.class);

    }
}
