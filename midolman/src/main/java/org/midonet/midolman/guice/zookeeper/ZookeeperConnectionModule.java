/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.guice.zookeeper;

import com.google.inject.*;
import com.google.inject.name.Names;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import org.midonet.config.ConfigProvider;
import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.ZkConnection;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.TryCatchReactor;

/**
 * Modules which creates the proper bindings for building a Directory backed up
 * by zookeeper.
 */
public class ZookeeperConnectionModule extends PrivateModule {
    @Override
    protected void configure() {

        binder().requireExplicitBindings();

        requireBinding(ConfigProvider.class);
        bind(ZookeeperConfig.class)
            .toProvider(ZookeeperConfigProvider.class)
            .asEagerSingleton();
        expose(ZookeeperConfig.class);

        bindZookeeperConnection();
        bindDirectory();
        bindReactor();

        bind(CuratorFramework.class)
            .toProvider(CuratorFrameworkProvider.class)
            .asEagerSingleton();
        expose(CuratorFramework.class);

        expose(Key.get(Reactor.class,
                       Names.named(
                           ZKConnectionProvider.DIRECTORY_REACTOR_TAG)));
        expose(Directory.class);

        bind(ZkConnectionAwareWatcher.class)
                .to(ZookeeperConnectionWatcher.class)
                .asEagerSingleton();
        expose(ZkConnectionAwareWatcher.class);
    }

    protected void bindDirectory() {
        bind(Directory.class)
            .toProvider(DirectoryProvider.class)
            .asEagerSingleton();
    }

    protected void bindZookeeperConnection() {
        bind(ZkConnection.class)
            .toProvider(ZKConnectionProvider.class)
            .asEagerSingleton();
    }

    protected void bindReactor() {
        bind(Reactor.class).annotatedWith(
            Names.named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG))
            .toProvider(ZookeeperReactorProvider.class)
            .asEagerSingleton();
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

    /**
     * A {@link Provider} of {@link ZookeeperConfig} instances which uses an
     * existing {@link ConfigProvider} as the configuration backend.
     */
    public static class ZookeeperConfigProvider implements
                                                Provider<ZookeeperConfig> {

        @Inject
        ConfigProvider configProvider;

        @Override
        public ZookeeperConfig get() {
            return configProvider.getConfig(ZookeeperConfig.class);
        }
    }

    public static class ZookeeperReactorProvider
        implements Provider<Reactor> {

        @Override
        public Reactor get() {
            return new TryCatchReactor("zookeeper", 1);
        }
    }
}
