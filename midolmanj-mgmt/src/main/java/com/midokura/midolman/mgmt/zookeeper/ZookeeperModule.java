/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.zookeeper;

import com.google.inject.*;
import com.google.inject.name.Names;

import com.midokura.config.ConfigProvider;
import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.guice.zookeeper.ReactorProvider;
import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.guice.zookeeper.ZookeeperConnectionModule;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkConnectionAwareWatcher;
import com.midokura.util.eventloop.Reactor;

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

        bind(Reactor.class).annotatedWith(
                Names.named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG))
                .toProvider(ReactorProvider.class)
                .asEagerSingleton();

        bind(ZkConnectionAwareWatcher.class)
                .to(ZookeeperConnWatcher.class)
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
