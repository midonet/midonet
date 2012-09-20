/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import org.apache.zookeeper.Watcher;

import com.midokura.cache.Cache;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.CacheFactory;
import com.midokura.midolman.SimulationController;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.services.DatapathConnectionService;
import com.midokura.midolman.services.MidolmanActorsService;
import com.midokura.midolman.services.MidolmanService;
import com.midokura.midolman.state.ZookeeperConnectionWatcher;
import com.midokura.midonet.cluster.Client;

/**
 * Main midolman configuration module
 */
public class MidolmanModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(ConfigProvider.class);
        requireBinding(Client.class);
        requireBinding(DatapathConnectionService.class);
        requireBinding(MidolmanActorsService.class);
        requireBinding(SimulationController.class);

        bind(MidolmanService.class).asEagerSingleton();
        expose(MidolmanService.class);

        bind(MidolmanConfig.class)
            .toProvider(MidolmanConfigProvider.class)
            .asEagerSingleton();
        expose(MidolmanConfig.class);

        bind(Cache.class)
            .toProvider(CacheProvider.class)
            .in(Singleton.class);
        expose(Cache.class);

        Named watcherAnnotation = Names.named(
                ZKConnectionProvider.WATCHER_NAME_TAG);

        bind(Watcher.class)
            .annotatedWith(watcherAnnotation)
            .to(ZookeeperConnectionWatcher.class)
            .asEagerSingleton();

        expose(Watcher.class)
            .annotatedWith(watcherAnnotation);
    }

    /**
     * A {@link Provider} of {@link MidolmanConfig} instances which uses an
     * existing {@link ConfigProvider} as the configuration backend.
     */
    public static class MidolmanConfigProvider
        implements Provider<MidolmanConfig> {
        @Inject
        ConfigProvider configProvider;

        @Override
        public MidolmanConfig get() {
            return configProvider.getConfig(MidolmanConfig.class);
        }
    }

    public static class CacheProvider implements Provider<Cache> {
        @Inject
        ConfigProvider configProvider;

        @Override
        public Cache get() {
            return CacheFactory.create(
                        configProvider.getConfig(MidolmanConfig.class));
        }
    }
}
