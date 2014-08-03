/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.guice.cluster;

import com.google.inject.Singleton;

import org.midonet.cluster.ClusterBgpManager;
import org.midonet.cluster.ClusterChainManager;
import org.midonet.cluster.ClusterConditionManager;
import org.midonet.cluster.ClusterHealthMonitorManager;
import org.midonet.cluster.ClusterIPAddrGroupManager;
import org.midonet.cluster.ClusterLoadBalancerManager;
import org.midonet.cluster.ClusterPoolHealthMonitorMapManager;
import org.midonet.cluster.ClusterPoolManager;


/**
 * Data cluster client module.  This class defines dependency bindings
 * for simple data access via DataClient interface.
 */
public class DataClusterClientModule extends DataClientModule {

    @Override
    protected void configure() {

        super.configure();

        binder().requireExplicitBindings();

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
    }
}
