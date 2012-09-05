/*
* Copyright 2012 Midokura PTE LTD.
*/
package com.midokura.midolman.guice.cluster;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Names;

import com.midokura.cassandra.CassandraClient;
import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.guice.MonitoringConfigurationProvider;
import com.midokura.midolman.monitoring.store.CassandraClientProvider;
import com.midokura.midolman.monitoring.store.CassandraStoreProvider;
import com.midokura.midolman.monitoring.store.Store;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PathBuilder;
import com.midokura.midolman.state.PortConfigCache;
import com.midokura.midolman.state.ZkConfigSerializer;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.zkManagers.AdRouteZkManager;
import com.midokura.midolman.state.zkManagers.BgpZkManager;
import com.midokura.midolman.state.zkManagers.BridgeDhcpZkManager;
import com.midokura.midolman.state.zkManagers.BridgeZkManager;
import com.midokura.midolman.state.zkManagers.ChainZkManager;
import com.midokura.midolman.state.zkManagers.PortGroupZkManager;
import com.midokura.midolman.state.zkManagers.PortSetZkManager;
import com.midokura.midolman.state.zkManagers.PortZkManager;
import com.midokura.midolman.state.zkManagers.RouteZkManager;
import com.midokura.midolman.state.zkManagers.RouterZkManager;
import com.midokura.midolman.state.zkManagers.RuleZkManager;
import com.midokura.midolman.state.zkManagers.TenantZkManager;
import com.midokura.midolman.state.zkManagers.TunnelZoneZkManager;
import com.midokura.midolman.state.zkManagers.VpnZkManager;
import com.midokura.midolman.util.JSONSerializer;
import com.midokura.midonet.cluster.ClusterBridgeManager;
import com.midokura.midonet.cluster.ClusterPortsManager;
import com.midokura.midonet.cluster.ClusterRouterManager;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.LocalDataClientImpl;
import com.midokura.midonet.cluster.services.MidostoreSetupService;
import com.midokura.util.eventloop.Reactor;

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

        bind(PathBuilder.class);
        bind(ZkConfigSerializer.class)
                .toInstance(new ZkConfigSerializer(new JSONSerializer()));

        bindZkManagers();
        bindCassandraStore();

        bind(DataClient.class).to(LocalDataClientImpl.class)
                .asEagerSingleton();
        expose(DataClient.class);


        bind(ClusterRouterManager.class)
                .in(Singleton.class);

        bind(ClusterBridgeManager.class)
                .in(Singleton.class);

        bind(ClusterPortsManager.class)
                .toProvider(ClusterPortsManagerProvider.class)
                .in(Singleton.class);

        bind(PortConfigCache.class)
                .toProvider(PortConfigCacheProvider.class)
                .in(Singleton.class);

        bind(MidostoreSetupService.class).in(Singleton.class);
        expose(MidostoreSetupService.class);
    }

    protected void bindCassandraStore() {
        requireBinding(MonitoringConfiguration.class);
        bind(MonitoringConfiguration.class).toProvider(
                MonitoringConfigurationProvider.class).asEagerSingleton();

        bind(CassandraClient.class).toProvider(CassandraClientProvider.class)
                .asEagerSingleton();
        bind(Store.class).toProvider(CassandraStoreProvider.class)
                .asEagerSingleton();
        expose(Store.class);
    }

    protected void bindZkManagers() {
        List<Class<? extends ZkManager>> managers = new ArrayList<Class<? extends ZkManager>>();

        managers.add(HostZkManager.class);
        managers.add(BgpZkManager.class);
        managers.add(RouterZkManager.class);
        managers.add(RouteZkManager.class);
        managers.add(RuleZkManager.class);
        managers.add(BridgeDhcpZkManager.class);
        managers.add(BridgeZkManager.class);
        managers.add(ChainZkManager.class);
        managers.add(PortZkManager.class);
        managers.add(AdRouteZkManager.class);
        managers.add(VpnZkManager.class);
        managers.add(PortGroupZkManager.class);
        managers.add(TenantZkManager.class);
        managers.add(TunnelZoneZkManager.class);
        managers.add(PortSetZkManager.class);

        for (Class<? extends ZkManager> managerClass : managers) {
            //noinspection unchecked
            bind(managerClass)
                    .toProvider(new ZkManagerProvider(managerClass))
                    .asEagerSingleton();
            expose(managerClass);
        }
    }

    private static class ZkManagerProvider<T extends ZkManager>
            implements Provider<T> {

        @Inject
        Directory directory;

        @Inject
        ZookeeperConfig config;

        Class<T> managerClass;

        protected ZkManagerProvider(Class<T> managerClass) {
            this.managerClass = managerClass;
        }

        @Override
        public T get() {
            try {
                Constructor<T> constructor =
                        managerClass.getConstructor(Directory.class,
                                String.class);

                return
                        constructor.newInstance(
                                directory,
                                config.getMidolmanRootKey());

            } catch (Exception e) {
                throw new RuntimeException(
                        "Could not create zkManager of class: "
                                + managerClass, e);
            }
        }
    }

    private static class ClusterPortsManagerProvider
            implements Provider<ClusterPortsManager> {
        @Inject
        PortZkManager portMgr;

        @Inject
        PortConfigCache portConfigCache;

        @Inject
        PortGroupZkManager portZkManager;


        @Override
        public ClusterPortsManager get() {
            ClusterPortsManager instance = new ClusterPortsManager();
            portConfigCache.addWatcher(instance.getPortsWatcher());
            return instance;
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

        @Override
        public PortConfigCache get() {
            return new PortConfigCache(reactor, directory, config.getMidolmanRootKey());
        }
    }
}
