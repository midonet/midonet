/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.cache.Cache;
import com.midokura.cache.CacheWithPrefix;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.CacheFactory;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.layer4.NatLeaseManager;
import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.layer4.NatMappingFactory;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.zkManagers.FiltersZkManager;
import com.midokura.util.eventloop.Reactor;

/**
 * Main midolman configuration module
 */
public class FlowStateCacheModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(ConfigProvider.class);
        requireBinding(Reactor.class);
        requireBinding(Directory.class);

        bindCache();

        bind(NatMappingFactory.class)
            .toProvider(NatMappingFactoryProvider.class)
            .asEagerSingleton();
        expose(NatMappingFactory.class);
    }

    protected void bindCache() {
        bind(Cache.class)
            .toProvider(CacheProvider.class)
            .in(Singleton.class);
        expose(Cache.class);
    }

    public static class CacheProvider implements Provider<Cache> {
        Logger log = LoggerFactory.getLogger(CacheProvider.class);

        @Inject
        ConfigProvider configProvider;

        @Override
        public Cache get() {
            try {
                return CacheFactory.create(
                        configProvider.getConfig(MidolmanConfig.class));
            } catch (Exception e) {
                log.error("Exception trying to create Cache:", e);
                return null;
            }
        }
    }

    private static class NatMappingFactoryProvider
            implements Provider<NatMappingFactory> {
        @Inject @Nullable
        private Cache cache;

        @Inject
        private Reactor reactor;

        @Inject
        private Directory zkDir;

        @Inject
        ConfigProvider configProvider;

        private static ConcurrentMap<UUID, NatMapping> natMappingMap =
                new ConcurrentHashMap<UUID, NatMapping>();

        public NatMappingFactory get() {
            final String zkBasePath =
                    configProvider.getConfig(ZookeeperConfig.class)
                            .getMidolmanRootKey();

            return new NatMappingFactory() {
                Logger log = LoggerFactory.getLogger(NatMappingFactory.class);

                public NatMapping get(final UUID ownerID) {
                    if (natMappingMap.containsKey(ownerID)) {
                        return natMappingMap.get(ownerID);
                    } else {
                        log.debug("Creating a new NatMapping for {}", ownerID);
                        NatMapping natMapping = new NatLeaseManager(
                                new FiltersZkManager(zkDir, zkBasePath),
                                ownerID,
                                new CacheWithPrefix(cache, ownerID.toString()),
                                reactor);
                        if (natMappingMap.putIfAbsent(ownerID, natMapping) == null)
                            return natMapping;
                        else
                            return get(ownerID);
                    }
                }
            };
        }
    }
}
