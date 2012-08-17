/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.data;

import com.google.inject.AbstractModule;
import com.midokura.midolman.mgmt.guice.data.cassandra.CassandraDaoModule;
import com.midokura.midolman.mgmt.guice.data.zookeeper.ZookeeperDaoModule;
import com.midokura.midostore.module.MidoStoreModule;

/**
 * DAO module
 */
public class DaoModule extends AbstractModule {

    @Override
    protected void configure() {

        // Install MidoStore
        install(new MidoStoreModule());

        // Install ZK DAO
        install(new ZookeeperDaoModule());

        // Install Cassandra DAO
        install(new CassandraDaoModule());
    }

}
