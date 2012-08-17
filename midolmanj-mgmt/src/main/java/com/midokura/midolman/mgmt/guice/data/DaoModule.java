/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.data;

import com.google.inject.AbstractModule;
import com.midokura.midolman.guice.cluster.ClusterClientModule;
import com.midokura.midolman.mgmt.guice.data.zookeeper.ZookeeperDaoModule;

/**
 * DAO module
 */
public class DaoModule extends AbstractModule {

    @Override
    protected void configure() {

        // Install Cluster client
        install(new ClusterClientModule());

        // Install ZK DAO
        install(new ZookeeperDaoModule());
    }

}
