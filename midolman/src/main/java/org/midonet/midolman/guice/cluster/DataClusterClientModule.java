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

import com.google.inject.Singleton;

import org.midonet.cluster.ClusterBgpManager;
import org.midonet.cluster.ClusterChainManager;
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
