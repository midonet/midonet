/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

import org.apache.curator.framework.CuratorFramework;

import org.midonet.cluster.BridgeBuilderStateFeeder;
import org.midonet.cluster.ClusterBridgeManager;
import org.midonet.cluster.ClusterHostManager;
import org.midonet.cluster.ClusterPortGroupManager;
import org.midonet.cluster.ClusterPortsManager;
import org.midonet.cluster.ClusterRouterManager;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.LocalDataClientImpl;
import org.midonet.cluster.ZookeeperLockFactory;
import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortConfigCache;
import org.midonet.midolman.state.PortGroupCache;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.AdRouteZkManager;
import org.midonet.midolman.state.zkManagers.BgpZkManager;
import org.midonet.midolman.state.zkManagers.BridgeDhcpV6ZkManager;
import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager;
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager;
import org.midonet.midolman.state.zkManagers.IpAddrGroupZkManager;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager;
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager;
import org.midonet.midolman.state.zkManagers.PoolZkManager;
import org.midonet.midolman.state.zkManagers.PortGroupZkManager;
import org.midonet.midolman.state.zkManagers.PortSetZkManager;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.state.zkManagers.RouteZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.state.zkManagers.RuleZkManager;
import org.midonet.midolman.state.zkManagers.TenantZkManager;
import org.midonet.midolman.state.zkManagers.TunnelZoneZkManager;
import org.midonet.midolman.state.zkManagers.VipZkManager;
import org.midonet.midolman.state.zkManagers.VtepZkManager;
import org.midonet.util.eventloop.Reactor;

import static org.midonet.midolman.guice.zookeeper.ZKConnectionProvider.DIRECTORY_REACTOR_TAG;

/**
 * Guice module to install dependencies for data access.
 */
public class DataClientModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(CuratorFramework.class); // for the zk lock factory
        requireBinding(Directory.class);
        requireBinding(Key.get(Reactor.class,
                               Names.named(DIRECTORY_REACTOR_TAG)));
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
        bind(ClusterRouterManager.class).in(Singleton.class);

        bind(ClusterBridgeManager.class).in(Singleton.class);

        bind(ClusterPortsManager.class).in(Singleton.class);

        bind(BridgeBuilderStateFeeder.class)
            .in(Singleton.class);

        bind(ClusterHostManager.class).in(Singleton.class);

        bind(PortConfigCache.class).toProvider(PortConfigCacheProvider.class)
                                   .in(Singleton.class);

        bind(PortGroupCache.class).toProvider(PortGroupCacheProvider.class)
                                  .in(Singleton.class);

        bind(ClusterPortGroupManager.class).in(Singleton.class);

        bindZookeeperLockFactory();
        expose(ZookeeperLockFactory.class);
    }

    /**
     * Bind a Zookeeper lock factory class.
     *
     * Override this method if you want to mock the factory class.
     */
    protected void bindZookeeperLockFactory() {
        bind(ZookeeperLockFactory.class).asEagerSingleton();
    }

    private static class PathBuilderProvider implements Provider<PathBuilder> {

        @Inject
        ZookeeperConfig config;

        @Override
        public PathBuilder get() {
            return new PathBuilder(config.getZkRootPath());
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
        managers.add(IpAddrGroupZkManager.class);
        managers.add(VtepZkManager.class);
        /*
         * The Cluster.*Managers managers still the L4LB zkmanagers.
         */
        managers.add(HealthMonitorZkManager.class);
        managers.add(LoadBalancerZkManager.class);
        managers.add(PoolZkManager.class);
        managers.add(PoolHealthMonitorZkManager.class);
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
        @Named(DIRECTORY_REACTOR_TAG)
        Reactor reactor;

        @Inject
        ZkConnectionAwareWatcher connWatcher;

        @Inject
        Serializer serializer;

        @Override
        public PortConfigCache get() {
            return new PortConfigCache(reactor, directory,
                    config.getZkRootPath(), connWatcher, serializer);
        }
    }

    private static class PortGroupCacheProvider
            implements Provider<PortGroupCache> {

        @Inject
        @Named(DIRECTORY_REACTOR_TAG)
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
