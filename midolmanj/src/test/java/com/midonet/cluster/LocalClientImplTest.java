/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midonet.cluster;

import java.util.Arrays;
import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.midokura.midolman.Setup;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.guice.cluster.ClusterClientModule;
import com.midokura.midolman.guice.config.MockConfigProviderModule;
import com.midokura.midolman.guice.config.TypedConfigModule;
import com.midokura.midolman.guice.reactor.ReactorModule;
import com.midokura.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.zkManagers.BridgeZkManager;
import com.midokura.midonet.cluster.Client;
import com.midokura.midonet.cluster.client.BridgeBuilder;
import com.midokura.midonet.cluster.client.Builder;
import com.midokura.midonet.cluster.client.DeviceBuilder;
import com.midokura.midonet.cluster.client.MacLearningTable;
import com.midokura.midonet.cluster.client.SourceNatResource;

public class LocalClientImplTest {

    @Inject
    Client client;
    Injector injector = null;
    String zkRoot = "/test/v3/midolman";


    HierarchicalConfiguration fillConfig(HierarchicalConfiguration config) {
        config.addNodes(ZookeeperConfig.GROUP_NAME,
                        Arrays.asList(new HierarchicalConfiguration.Node
                                      ("midolman_root_key", zkRoot)));
        return config;

    }

    BridgeZkManager getBridgeZkManager() {
        return injector.getInstance(BridgeZkManager.class);
    }

    Directory zkDir() {
        return injector.getInstance(Directory.class);
    }

    @Before
    public void initialize() {
        HierarchicalConfiguration config = fillConfig(
            new HierarchicalConfiguration());
        injector = Guice.createInjector(
            new MockConfigProviderModule(config),
            new MockZookeeperConnectionModule(),
            new TypedConfigModule<MidolmanConfig>(MidolmanConfig.class),

            new ReactorModule(),
            new ClusterClientModule()
        );
        injector.injectMembers(this);

    }


    @Test
    public void getBridgeTest()
        throws StateAccessException, InterruptedException, KeeperException {

        initializeZKStructure();
        Setup.createZkDirectoryStructure(zkDir(), zkRoot);
        UUID bridgeId = getBridgeZkManager().create(
            new BridgeZkManager.BridgeConfig());
        TestBridgeBuilder bridgeBuilder = new TestBridgeBuilder();
        client.getBridge(bridgeId, bridgeBuilder);
        Thread.sleep(2000);
        assertThat("Build is called", bridgeBuilder.getBuildCallsCount(),
                   equalTo(1));
        // let's cause a bridge update
        getBridgeZkManager().update(bridgeId, new BridgeZkManager.BridgeConfig(
            UUID.randomUUID(), UUID.randomUUID()));
        Thread.sleep(2000);
        assertThat("Bridge update was notified",
                   bridgeBuilder.getBuildCallsCount(), equalTo(2));

    }

    void initializeZKStructure() throws InterruptedException, KeeperException {

        String[] nodes = zkRoot.split("/");
        String path = "/";
        for (String node : nodes) {
            if (!node.isEmpty()) {
                zkDir().add(path + node, null, CreateMode.PERSISTENT);
                path += node;
                path += "/";
            }
        }
    }

    class TestBridgeBuilder implements BridgeBuilder {
        int buildCallsCount = 0;

        public int getBuildCallsCount() {
            return buildCallsCount;
        }

        @Override
        public void setTunnelKey(long key) {
        }

        @Override
        public void setMacLearningTable(MacLearningTable table) {
        }

        @Override
        public void setSourceNatResource(SourceNatResource resource) {
        }

        @Override
        public DeviceBuilder setID(UUID id) {
            return this;
        }

        @Override
        public DeviceBuilder setInFilter(UUID filterID) {
            return this;
        }

        @Override
        public DeviceBuilder setOutFilter(UUID filterID) {
            return this;
        }

        @Override
        public Builder start() {
            return this;
        }

        @Override
        public void build() {
            buildCallsCount++;
        }

    }
}
