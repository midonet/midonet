/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.BindingAnnotation;
import com.google.inject.Key;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkLock;
import org.midonet.midolman.state.ZkManager;
import org.midonet.util.eventloop.TryCatchReactor;
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

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Main midolman configuration module
 */
public class CacheModule extends PrivateModule {
    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface CACHE_REACTOR {}

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(ConfigProvider.class);
        requireBinding(Directory.class);

        bind(Reactor.class).
                annotatedWith(CACHE_REACTOR.class).
                toInstance(new TryCatchReactor("cache-reactor", 1));
        expose(Key.get(Reactor.class, CACHE_REACTOR.class));

        bindCache();

        bind(NatMappingFactory.class)
            .toProvider(NatMappingFactoryProvider.class)
            .asEagerSingleton();
        expose(NatMappingFactory.class);
    }

    protected void bindCache() {
        bind(Cache.class).annotatedWith(NAT_CACHE.class)
            .toProvider(new CacheProvider("nat", 60))
            .in(Singleton.class);
        bind(Cache.class).annotatedWith(TRACE_MESSAGES.class)
            .toProvider(new CacheProvider("trace_messages", 604800))
            .in(Singleton.class);
        bind(Cache.class).annotatedWith(TRACE_INDEX.class)
            .toProvider(new CacheProvider("trace_index", 604800))
            .in(Singleton.class);
        expose(Key.get(Cache.class, NAT_CACHE.class));
        expose(Key.get(Cache.class, TRACE_MESSAGES.class));
        expose(Key.get(Cache.class, TRACE_INDEX.class));
    }

    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface NAT_CACHE {}
    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface TRACE_MESSAGES {}
    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface TRACE_INDEX {}

    public static class CacheProvider implements Provider<Cache> {
        Logger log = LoggerFactory.getLogger(CacheProvider.class);
        String columnName;
        int cacheExpirationSeconds;

        @Inject
        ConfigProvider configProvider;

        @Inject
        ZkManager zk;

        @Inject
        PathBuilder paths;

        @Inject @CACHE_REACTOR
        private Reactor reactor;

        CacheProvider(String columnName_, int cacheExpirationSeconds_) {
            columnName = columnName_;
            cacheExpirationSeconds = cacheExpirationSeconds_;
        }

        @Override
        public Cache get() {
            try {
                return CacheFactory.create(
                        configProvider.getConfig(MidolmanConfig.class),
                        columnName, cacheExpirationSeconds, reactor,
                        new ZkLock(zk, paths, "cassandra-cache"));
            } catch (Exception e) {
                log.error("Exception trying to create Cache:", e);
                return null;
            }
        }
    }

    private static class NatMappingFactoryProvider
            implements Provider<NatMappingFactory> {
        @Inject @Nullable @NAT_CACHE
        private Cache cache;

        @Inject @CACHE_REACTOR
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
