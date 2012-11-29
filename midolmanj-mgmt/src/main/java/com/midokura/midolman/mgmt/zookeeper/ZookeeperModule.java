/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.zookeeper;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.name.Names;

import com.midokura.config.ConfigProvider;
import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.guice.zookeeper.ZookeeperConnectionModule;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkConnectionAwareWatcher;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.eventloop.TryCatchReactor;

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

        bind(ZkConnectionAwareWatcher.class)
                .to(ZookeeperConnWatcher.class)
                .asEagerSingleton();

        // Bind the ZK connection with watcher
        bind(ZkConnection.class).toProvider(
                ZkConnectionProvider.class).asEagerSingleton();

        // Bind the Directory object
        bind(Directory.class).toProvider(
                ExtendedDirectoryProvider.class).asEagerSingleton();

        bind(Reactor.class).annotatedWith(
                Names.named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG))
                .toProvider(ZookeeperReactorProvider.class)
                .asEagerSingleton();
    }

    @Inject
    @Provides
    ExtendedZookeeperConfig provideZookeeperExtendedConfig(
            ConfigProvider provider) {
        return provider.getConfig(ExtendedZookeeperConfig.class);
    }

    public static class ZookeeperReactorProvider
        implements Provider<Reactor> {

        @Override
        public Reactor get() {
            return new TryCatchReactor("zookeeper-mgmt", 1);
        }
    }
}
