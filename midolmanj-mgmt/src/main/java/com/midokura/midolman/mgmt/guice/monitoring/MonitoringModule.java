/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.monitoring;

import com.google.inject.AbstractModule;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.mgmt.data.dao.MetricDao;
import com.midokura.midolman.mgmt.data.zookeeper.dao.MetricCassandraDao;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.guice.MonitoringConfigurationProvider;
import com.midokura.midolman.monitoring.store.Store;

/**
 * Monitoring module
 */
public class MonitoringModule extends AbstractModule {

    @Override
    protected void configure() {

        requireBinding(ConfigProvider.class);

        bind(MonitoringConfiguration.class).toProvider(
                MonitoringConfigurationProvider.class).asEagerSingleton();

        // CassandraStore
        bind(Store.class).toProvider(
                MonitoringStoreProvider.class).asEagerSingleton();

        bind(MetricDao.class).to(MetricCassandraDao.class).asEagerSingleton();
    }

}
