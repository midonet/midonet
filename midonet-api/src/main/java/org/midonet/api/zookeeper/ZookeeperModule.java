/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.zookeeper;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Names;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import org.midonet.api.rest_api.HttpAwareZoom;
import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.cluster.data.storage.StorageService;
import org.midonet.midolman.guice.zookeeper.DirectoryProvider;
import org.midonet.midolman.guice.zookeeper.ZKConnectionProvider;
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.ZkConnection;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.TryCatchReactor;

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
            DirectoryProvider.class).asEagerSingleton();

        bind(CuratorFramework.class)
            .toProvider(CuratorFrameworkProvider.class)
            .asEagerSingleton();

        bind(StorageService.class)
            .toProvider(StorageServiceProvider.class)
            .asEagerSingleton();

        bind(Reactor.class).annotatedWith(
            Names.named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG))
                .toProvider(ZookeeperReactorProvider.class)
                .asEagerSingleton();
    }

    public static class ZookeeperReactorProvider
        implements Provider<Reactor> {

        @Override
        public Reactor get() {
            return new TryCatchReactor("zookeeper-mgmt", 1);
        }
    }

    public static class StorageServiceProvider
        implements Provider<StorageService> {
        @Inject ZookeeperConfig cfg;
        @Inject CuratorFramework curator;
        @Override public StorageService get() {
            return new HttpAwareZoom(cfg.getZkRootPath() + "/zoom", curator);
        }
    }

    public static class CuratorFrameworkProvider
        implements Provider<CuratorFramework> {
        private ZookeeperConfig cfg;
        @Inject
        public CuratorFrameworkProvider(ZookeeperConfig cfg) {
            this.cfg = cfg;
        }
        @Override
        public CuratorFramework get() {
            // DO not start, the MidostoreSetupService will take care of that
            return CuratorFrameworkFactory.newClient(
                cfg.getZkHosts(), new ExponentialBackoffRetry(1000, 10)
            );
        }
    }
}
