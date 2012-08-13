/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.data;

import com.google.inject.AbstractModule;
import com.midokura.midolman.guice.zookeeper.DirectoryProvider;
import com.midokura.midolman.mgmt.data.zookeeper.ZookeeperService;
import com.midokura.midolman.mgmt.guice.data.zookeeper.ZkConnectionProvider;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;

/**
 * DataStore module
 */
public class DataStoreModule extends AbstractModule {

    @Override
    protected void configure() {
        bindServices();
    }

    /*
     * DataStore defaults to Zookeeper.  override this method to mock.
     */
    protected void bindServices() {

        // Bind the ZK connection with watcher
        bind(ZkConnection.class).toProvider(
                ZkConnectionProvider.class).asEagerSingleton();

        // Bind the Directory object
        bind(Directory.class).toProvider(
                DirectoryProvider.class).asEagerSingleton();

        // ZK service
        bind(ZookeeperService.class).asEagerSingleton();

    }
}
