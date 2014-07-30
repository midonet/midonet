/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
import com.google.inject.name.Named;
import com.google.inject.name.Names;

import org.midonet.cluster.*;
import org.midonet.cluster.data.neutron.*;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.guice.zookeeper.ZKConnectionProvider;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortConfigCache;
import org.midonet.midolman.state.PortGroupCache;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.*;
import org.midonet.util.eventloop.Reactor;

/**
 * Guice module to install dependencies for data access.
 */
public class DataClientModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(Directory.class);
        requireBinding(Key.get(Reactor.class, Names.named(
                ZKConnectionProvider.DIRECTORY_REACTOR_TAG)));
        requireBinding(ZkConnectionAwareWatcher.class);

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

        // TODO: Move these out of LocalDataClientImpl so that they can be
        // installed in DataClusterClientModule instead.
        bind(ClusterRouterManager.class)
                .in(Singleton.class);

        bind(ClusterBridgeManager.class)
                .in(Singleton.class);

        bind(ClusterPortsManager.class)
                .in(Singleton.class);

        bind(ClusterHostManager.class)
                .in(Singleton.class);

        bind(PortConfigCache.class)
                .toProvider(PortConfigCacheProvider.class)
                .in(Singleton.class);

        bind(NetworkZkManager.class).in(Singleton.class);
        expose(NetworkZkManager.class);

        bind(L3ZkManager.class).in(Singleton.class);
        expose(L3ZkManager.class);

        bind(ProviderRouterZkManager.class).in(Singleton.class);
        expose(ProviderRouterZkManager.class);

        bind(ExternalNetZkManager.class).in(Singleton.class);
        expose(ExternalNetZkManager.class);

        bind(SecurityGroupZkManager.class).in(Singleton.class);
        expose(SecurityGroupZkManager.class);

        bind(LoadBalancerApi.class).to(NeutronPlugin.class).asEagerSingleton();
        expose(LoadBalancerApi.class);

        bind(PortGroupCache.class).toProvider(PortGroupCacheProvider.class)
                .in(Singleton.class);

        bind(ClusterPortGroupManager.class)
                .in(Singleton.class);
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
        List<Class<? extends BaseZkManager>> managers = new ArrayList<>();

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
        managers.add(TaggableConfigZkManager.class);
        managers.add(TraceConditionZkManager.class);
        managers.add(IpAddrGroupZkManager.class);
        managers.add(VtepZkManager.class);
        managers.add(LicenseZkManager.class);
        /*
         * The Cluster.*Managers managers still the L4LB zkmanagers.
         */
        managers.add(HealthMonitorZkManager.class);
        managers.add(LoadBalancerZkManager.class);
        managers.add(PoolZkManager.class);
        managers.add(PoolMemberZkManager.class);
        managers.add(VipZkManager.class);

        for (Class<? extends BaseZkManager> managerClass : managers) {
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

        @Inject
        ZookeeperConfig config;

        @Override
        public ZkManager get() {
            return new ZkManager(directory, config);
        }
    }

    private static class ZkManagerProvider<T extends BaseZkManager>
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
        @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
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

    private static class PortGroupCacheProvider
            implements Provider<PortGroupCache> {

        @Inject
        @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
        Reactor reactor;

        @Inject
        ZkConnectionAwareWatcher connWatcher;

        @Inject
        Serializer serializer;

        @Inject
        PortGroupZkManager portGroupMgr;

        @Override
        public PortGroupCache get() {
            return new PortGroupCache(reactor, portGroupMgr,
                                      connWatcher, serializer);
        }
    }
}
