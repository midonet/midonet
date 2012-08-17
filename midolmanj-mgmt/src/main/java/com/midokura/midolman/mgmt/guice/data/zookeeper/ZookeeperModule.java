/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.data.zookeeper;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.guice.zookeeper.ZookeeperConnectionModule;
import com.midokura.midolman.mgmt.data.zookeeper.ExtendedZookeeperConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;

/**
 * Zookeeper module
 */
public class ZookeeperModule extends AbstractModule {

    @Override
    protected void configure() {

        // Bind ZK Config
        bind(ZookeeperConfig.class).toProvider(
                ZookeeperConnectionModule.ZookeeperConfigProvider.class)
                .asEagerSingleton();

        // Bind the ZK connection with watcher
        bind(ZkConnection.class).toProvider(
                ZkConnectionProvider.class).asEagerSingleton();

        // Bind the Directory object
        bind(Directory.class).toProvider(
                ExtendedDirectoryProvider.class).asEagerSingleton();
    }

    @Inject
    @Provides
    ExtendedZookeeperConfig provideZookeeperExtendedConfig(
            ConfigProvider provider) {
        return provider.getConfig(ExtendedZookeeperConfig.class);
    }
}
