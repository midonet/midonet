/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice;

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

import org.midonet.cache.Cache;
import org.midonet.cache.CacheWithPrefix;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.CacheFactory;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.layer4.NatLeaseManager;
import org.midonet.midolman.layer4.NatMapping;
import org.midonet.midolman.layer4.NatMappingFactory;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.zkManagers.FiltersZkManager;
import org.midonet.util.eventloop.Reactor;


/**
 * Main midolman configuration module
 */
public class CacheModule extends PrivateModule {

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
        bind(Cache.class).annotatedWith(NAT_CACHE.class)
            .toProvider(new CacheProvider("nat"))
            .in(Singleton.class);
        bind(Cache.class).annotatedWith(TRACE_MESSAGES.class)
            .toProvider(new CacheProvider("trace_messages"))
            .in(Singleton.class);
        bind(Cache.class).annotatedWith(TRACE_INDEX.class)
            .toProvider(new CacheProvider("trace_index"))
            .in(Singleton.class);
        expose(Cache.class);
    }

    public @interface NAT_CACHE {}
    public @interface TRACE_MESSAGES {}
    public @interface TRACE_INDEX {}

    public static class CacheProvider implements Provider<Cache> {
        Logger log = LoggerFactory.getLogger(CacheProvider.class);
        String columnName;

        @Inject
        ConfigProvider configProvider;

        CacheProvider(String columnName_) {
            columnName = columnName_;
        }

        @Override
        public Cache get() {
            try {
                return CacheFactory.create(
                        configProvider.getConfig(MidolmanConfig.class),
                        columnName);
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
        private ConfigProvider configProvider;

        @Inject
        private Serializer serializer;

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
                    } else if (cache == null) {
                        log.warn("Not creating a NatMapping because cache is " +
                                 "null.");
                        return null;
                    } else {
                        log.debug("Creating a new NatMapping for {}", ownerID);
                        NatMapping natMapping = new NatLeaseManager(
                                new FiltersZkManager(zkDir, zkBasePath, serializer),
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
