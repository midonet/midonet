/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.cluster;

import java.util.Arrays;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;

import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.midolman.Setup;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.cluster.DataClusterClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.config.TypedConfigModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.packets.IPv4Subnet;

public class LocalDataClientImplTestBase {

    @Inject protected DataClient client;
    Injector injector = null;
    String zkRoot = "/test/v3/midolman";


    HierarchicalConfiguration fillConfig(HierarchicalConfiguration config) {
        config.addNodes(ZookeeperConfig.GROUP_NAME,
                Arrays.asList(new HierarchicalConfiguration.Node
                        ("midolman_root_key", zkRoot)));
        return config;
    }

    Directory zkDir() {
        return injector.getInstance(Directory.class);
    }

    @Before
    public void initialize() throws InterruptedException, KeeperException {
        HierarchicalConfiguration config = fillConfig(
                new HierarchicalConfiguration());
        injector = Guice.createInjector(
                new VersionModule(),
                new SerializationModule(),
                new ConfigProviderModule(config),
                new MockZookeeperConnectionModule(),
                new TypedConfigModule<>(MidolmanConfig.class),
                new DataClusterClientModule()
        );
        injector.injectMembers(this);
        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
    }

    protected Bridge getStockBridge() {
        return new Bridge()
                .setAdminStateUp(true);
    }

    protected Subnet getStockSubnet(String cidr) {
        return new Subnet().setSubnetAddr(IPv4Subnet.fromCidr(cidr));
    }

}

