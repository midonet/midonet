/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.cluster;

import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortConfigCache;
import com.midokura.midolman.state.zkManagers.PortGroupZkManager;
import com.midokura.midolman.state.zkManagers.PortZkManager;
import com.midokura.midonet.cluster.*;
import com.midokura.util.eventloop.Reactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class defines dependency bindings for DataClient and Client
 * interfaces.  It extends DataClusterClientModule that defines bindings
 * for DataClient, and it defines the bindings specific to Client.
 */
public class ClusterClientModule extends DataClusterClientModule {

    private static final Logger log = LoggerFactory
            .getLogger(ClusterClientModule.class);

    @Override
    protected void configure() {
        super.configure();

        requireBinding(Directory.class);

        bind(Client.class)
                .to(LocalClientImpl.class)
                .asEagerSingleton();
        expose(Client.class);
    }
}
