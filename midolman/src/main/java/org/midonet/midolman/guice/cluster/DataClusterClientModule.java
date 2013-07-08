/*
* Copyright 2012 Midokura PTE LTD.
*/
package org.midonet.midolman.guice.cluster;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import org.midonet.cache.Cache;
import org.midonet.cluster.ClusterBgpManager;
import org.midonet.cluster.ClusterBridgeManager;
import org.midonet.cluster.ClusterChainManager;
import org.midonet.cluster.ClusterConditionManager;
import org.midonet.cluster.ClusterPortsManager;
import org.midonet.cluster.ClusterRouterManager;
import org.midonet.cluster.ClusterVlanBridgeManager;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.LocalDataClientImpl;
import org.midonet.cluster.services.MidostoreSetupService;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.guice.zookeeper.ZKConnectionProvider;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.monitoring.store.Store;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortConfigCache;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.*;
import org.midonet.util.eventloop.Reactor;
import static org.midonet.midolman.guice.CacheModule.TRACE_INDEX;
import static org.midonet.midolman.guice.CacheModule.TRACE_MESSAGES;


/**
 * Data cluster client module.  This class defines dependency bindings
 * for simple data access via DataClient interface.
 */
public class DataClusterClientModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(Directory.class);
        requireBinding(Key.get(Reactor.class, Names.named(
            ZKConnectionProvider.DIRECTORY_REACTOR_TAG)));
        requireBinding(ZkConnectionAwareWatcher.class);
        requireBinding(Store.class);
        requireBinding(Key.get(Cache.class, TRACE_INDEX.class));
        requireBinding(Key.get(Cache.class, TRACE_MESSAGES.class));

        bind(PathBuilder.class).toProvider(PathBuilderProvider.class)
            .asEagerSingleton();
        expose(PathBuilder.class);

        bind(ZkManager.class).toProvider(BaseZkManagerProvider.class)
                .asEagerSingleton();
        expose(ZkManager.class);
        bindZkManagers();

        bind(DataClient.class).to(LocalDataClientImpl.class)
                .asEagerSingleton();
        expose(DataClient.class);

        bind(ClusterRouterManager.class)
                .in(Singleton.class);

        bind(ClusterBridgeManager.class)
                .in(Singleton.class);

        bind(ClusterVlanBridgeManager.class)
            .in(Singleton.class);

        bind(ClusterBgpManager.class)
            .in(Singleton.class);

        bind(ClusterChainManager.class)
            .in(Singleton.class);

        bind(ClusterConditionManager.class)
            .in(Singleton.class);

        bind(ClusterPortsManager.class)
                    .in(Singleton.class);

        bind(PortConfigCache.class)
                .toProvider(PortConfigCacheProvider.class)
                .in(Singleton.class);

        bind(MidostoreSetupService.class).in(Singleton.class);
        expose(MidostoreSetupService.class);
    }

    private static class PathBuilderProvider implements Provider<PathBuilder> {

        @Inject
        ZookeeperConfig config;

        @Override
        public PathBuilder get() {
            return new PathBuilder(config.getMidolmanRootKey());
        }
    }

    protected void bindZkManagers() {
        List<Class<? extends AbstractZkManager>> managers =
                new ArrayList<Class<? extends AbstractZkManager>>();

        managers.add(HostZkManager.class);
        managers.add(BgpZkManager.class);
        managers.add(RouterZkManager.class);
        managers.add(RouteZkManager.class);
        managers.add(RuleZkManager.class);
        managers.add(BridgeDhcpZkManager.class);
        managers.add(BridgeDhcpV6ZkManager.class);
        managers.add(BridgeZkManager.class);
        managers.add(ChainZkManager.class);
        managers.add(PortZkManager.class);
        managers.add(AdRouteZkManager.class);
        managers.add(PortGroupZkManager.class);
        managers.add(TenantZkManager.class);
        managers.add(TunnelZoneZkManager.class);
        managers.add(PortSetZkManager.class);
        managers.add(VlanAwareBridgeZkManager.class);
        managers.add(TaggableConfigZkManager.class);
        managers.add(TraceConditionZkManager.class);

        for (Class<? extends AbstractZkManager> managerClass : managers) {
            //noinspection unchecked
            bind(managerClass)
                    .toProvider(new ZkManagerProvider(managerClass))
                    .asEagerSingleton();
            expose(managerClass);
        }
    }

    private static class BaseZkManagerProvider implements Provider<ZkManager> {

        @Inject
        Directory directory;

        @Override
        public ZkManager get() {
            return new ZkManager(directory);
        }
    }

    private static class ZkManagerProvider<T extends AbstractZkManager>
            implements Provider<T> {

        @Inject
        ZkManager zk;

        @Inject
        PathBuilder paths;

        @Inject
        Serializer serializer;

        Class<T> managerClass;

        protected ZkManagerProvider(Class<T> managerClass) {
            this.managerClass = managerClass;
        }

        @Override
        public T get() {
            try {
                Constructor<T> constructor = managerClass.getConstructor(
                               ZkManager.class, PathBuilder.class,
                               Serializer.class);

                return constructor.newInstance(zk, paths, serializer);

            } catch (Exception e) {
                throw new RuntimeException(
                        "Could not create zkManager of class: "
                                + managerClass, e);
            }
        }
    }

    private static class PortConfigCacheProvider
            implements Provider<PortConfigCache> {

        @Inject
        Directory directory;

        @Inject
        ZookeeperConfig config;

        @Inject
        Reactor reactor;

        @Inject
        ZkConnectionAwareWatcher connWatcher;

        @Inject
        Serializer serializer;

        @Override
        public PortConfigCache get() {
            return new PortConfigCache(reactor, directory,
                    config.getMidolmanRootKey(), connWatcher, serializer);
        }
    }
}
