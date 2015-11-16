
package org.midonet.cluster;

import java.lang.reflect.Constructor;

import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.name.Names;

import org.midonet.cluster.backend.Directory;
import org.midonet.cluster.backend.zookeeper.ZkConnectionAwareWatcher;
import org.midonet.cluster.state.LegacyStorage;
import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.PathBuilder;
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
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.state.zkManagers.RouteZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.state.zkManagers.RuleZkManager;
import org.midonet.midolman.state.zkManagers.TenantZkManager;
import org.midonet.midolman.state.zkManagers.TraceRequestZkManager;
import org.midonet.midolman.state.zkManagers.TunnelZoneZkManager;
import org.midonet.midolman.state.zkManagers.VipZkManager;
import org.midonet.midolman.state.zkManagers.VtepZkManager;
import org.midonet.util.eventloop.Reactor;


import static org.midonet.midolman.cluster.zookeeper.ZkConnectionProvider.DIRECTORY_REACTOR_TAG;

public class LegacyDataClientModule extends PrivateModule {
    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(MidonetBackendConfig.class);
        requireBinding(Directory.class);
        requireBinding(Key.get(Reactor.class,
                               Names.named(DIRECTORY_REACTOR_TAG)));
        requireBinding(ZkConnectionAwareWatcher.class);
        requireBinding(LegacyStorage.class);
        requireBinding(PathBuilder.class);
        requireBinding(ZkManager.class);

        bindZkManagers();
        bind(DataClient.class).to(LocalDataClientImpl.class)
                              .asEagerSingleton();
        expose(DataClient.class);
    }

    protected void bindZkManagers() {
        bindZkManager(HostZkManager.class);
        bindZkManager(BgpZkManager.class);
        bindZkManager(RouterZkManager.class);
        bindZkManager(RouteZkManager.class);
        bindZkManager(RuleZkManager.class);
        bindZkManager(BridgeDhcpZkManager.class);
        bindZkManager(BridgeDhcpV6ZkManager.class);
        bindZkManager(BridgeZkManager.class);
        bindZkManager(ChainZkManager.class);
        bindZkManager(PortZkManager.class);
        bindZkManager(AdRouteZkManager.class);
        bindZkManager(PortGroupZkManager.class);
        bindZkManager(TenantZkManager.class);
        bindZkManager(TunnelZoneZkManager.class);
        bindZkManager(IpAddrGroupZkManager.class);
        bindZkManager(VtepZkManager.class);
        bindZkManager(TraceRequestZkManager.class);
        /*
         * The Cluster.*Managers managers still the L4LB zkmanagers.
         */
        bindZkManager(HealthMonitorZkManager.class);
        bindZkManager(LoadBalancerZkManager.class);
        bindZkManager(PoolZkManager.class);
        bindZkManager(PoolHealthMonitorZkManager.class);
        bindZkManager(PoolMemberZkManager.class);
        bindZkManager(VipZkManager.class);
    }

    private <T extends BaseZkManager> void bindZkManager(Class<T> managerClass) {
        bind(managerClass)
            .toProvider(new ZkManagerProvider<T>(managerClass))
            .asEagerSingleton();
        expose(managerClass);
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
}
