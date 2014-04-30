/*
 * Copyright (c) 2012-2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.guice.cluster;

import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.midonet.cache.Cache;
import org.midonet.cluster.ClusterBgpManager;
import org.midonet.cluster.ClusterBridgeManager;
import org.midonet.cluster.ClusterChainManager;
import org.midonet.cluster.ClusterConditionManager;
import org.midonet.cluster.ClusterHealthMonitorManager;
import org.midonet.cluster.ClusterIPAddrGroupManager;
import org.midonet.cluster.ClusterLoadBalancerManager;
import org.midonet.cluster.ClusterPoolHealthMonitorMapManager;
import org.midonet.cluster.ClusterPoolManager;
import org.midonet.cluster.ClusterPortsManager;
import org.midonet.cluster.ClusterRouterManager;
import org.midonet.cluster.services.MidostoreSetupService;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.guice.CacheModule;
import org.midonet.midolman.guice.zookeeper.ZKConnectionProvider;
import org.midonet.midolman.monitoring.store.Store;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PortConfigCache;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.midonet.util.eventloop.Reactor;


/**
 * Data cluster client module.  This class defines dependency bindings
 * for simple data access via DataClient interface.
 */
public class DataClusterClientModule extends DataClientModule {

    @Override
    protected void configure() {

        super.configure();

        binder().requireExplicitBindings();

        requireBinding(Key.get(Cache.class, CacheModule.TRACE_INDEX.class));
        requireBinding(Key.get(Cache.class, CacheModule.TRACE_MESSAGES.class));

        bind(ClusterBgpManager.class)
                .in(Singleton.class);

        bind(ClusterChainManager.class)
                .in(Singleton.class);

        bind(ClusterConditionManager.class)
                .in(Singleton.class);

        bind(ClusterIPAddrGroupManager.class)
                .in(Singleton.class);

        bind(ClusterLoadBalancerManager.class)
                .in(Singleton.class);

        bind(ClusterPoolManager.class)
                .in(Singleton.class);

        bind(ClusterPoolHealthMonitorMapManager.class)
                .in(Singleton.class);

        bind(ClusterHealthMonitorManager.class)
                .in(Singleton.class);

        bind(MidostoreSetupService.class).in(Singleton.class);
        expose(MidostoreSetupService.class);
    }
}
