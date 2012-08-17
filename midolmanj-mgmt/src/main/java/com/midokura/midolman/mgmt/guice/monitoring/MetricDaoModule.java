/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.monitoring;

import com.google.inject.AbstractModule;
import com.midokura.midolman.mgmt.data.dao.MetricDao;
import com.midokura.midolman.mgmt.data.zookeeper.dao.MetricCassandraDao;
import com.midokura.midolman.monitoring.store.Store;

/**
 * Cassandra DAO module
 */
public class MetricDaoModule extends AbstractModule {

    @Override
    protected void configure() {

        requireBinding(Store.class);

        // Metric
        bind(MetricDao.class).to(MetricCassandraDao.class).asEagerSingleton();

    }

}
