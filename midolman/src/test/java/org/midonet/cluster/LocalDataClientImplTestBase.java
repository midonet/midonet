/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.cluster;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.midonet.cluster.data.l4lb.HealthMonitor;
import org.midonet.cluster.data.l4lb.LoadBalancer;
import org.midonet.cluster.data.l4lb.Pool;
import org.midonet.cluster.data.l4lb.PoolMember;
import org.midonet.cluster.data.l4lb.VIP;
import org.midonet.midolman.Setup;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.guice.CacheModule;
import org.midonet.midolman.guice.MockMonitoringStoreModule;
import org.midonet.midolman.guice.cluster.DataClusterClientModule;
import org.midonet.midolman.guice.config.MockConfigProviderModule;
import org.midonet.midolman.guice.config.TypedConfigModule;
import org.midonet.midolman.guice.reactor.ReactorModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager;
import org.midonet.midolman.state.zkManagers.PoolZkManager;
import org.midonet.midolman.state.zkManagers.RouteZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.state.zkManagers.VipZkManager;
import org.midonet.midolman.version.guice.VersionModule;

import java.util.Arrays;
import java.util.UUID;

public class LocalDataClientImplTestBase {

    @Inject
    DataClient client;
    Injector injector = null;
    String zkRoot = "/test/v3/midolman";


    HierarchicalConfiguration fillConfig(HierarchicalConfiguration config) {
        config.addNodes(ZookeeperConfig.GROUP_NAME,
                Arrays.asList(new HierarchicalConfiguration.Node
                        ("midolman_root_key", zkRoot)));
        return config;

    }

    RouteZkManager getRouteZkManager() {
        return injector.getInstance(RouteZkManager.class);
    }

    RouterZkManager getRouterZkManager() {
        return injector.getInstance(RouterZkManager.class);
    }

    PoolZkManager getPoolZkManager() {
        return injector.getInstance(PoolZkManager.class);
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
                new MockConfigProviderModule(config),
                new MockZookeeperConnectionModule(),
                new TypedConfigModule<>(MidolmanConfig.class),
                new CacheModule(),
                new ReactorModule(),
                new MockMonitoringStoreModule(),
                new DataClusterClientModule()
        );
        injector.injectMembers(this);
        String[] nodes = zkRoot.split("/");
        String path = "/";

        for (String node : nodes) {
            if (!node.isEmpty()) {
                zkDir().add(path + node, null, CreateMode.PERSISTENT);
                path += node;
                path += "/";
            }
        }
        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
    }

    protected HealthMonitor getStockHealthMonitor() {
        return new HealthMonitor()
                .setDelay(100)
                .setMaxRetries(100)
                .setTimeout(1000);
    }

    protected UUID createStockHealthMonitor()
            throws SerializationException, StateAccessException {
        return client.healthMonitorCreate(getStockHealthMonitor());
    }

    protected LoadBalancer getStockLoadBalancer() {
        return new LoadBalancer();
    }

    protected UUID createStockLoadBalancer()
            throws InvalidStateOperationException, SerializationException,
            StateAccessException {
        return client.loadBalancerCreate(getStockLoadBalancer());
    }

    protected Pool getStockPool(UUID loadBalancerId) {
        return new Pool().setLoadBalancerId(loadBalancerId)
                .setLbMethod("ROUND_ROBIN")
                .setProtocol("TCP");
    }

    protected UUID createStockPool(UUID loadBalancerId)
            throws SerializationException, StateAccessException {
        return client.poolCreate(getStockPool(loadBalancerId));
    }

    protected PoolMember getStockPoolMember(UUID poolId) {
        return new PoolMember()
                .setPoolId(poolId)
                .setAddress("192.168.10.1")
                .setProtocolPort(80)
                .setWeight(100);
    }

    protected UUID createStockPoolMember(UUID poolId)
            throws SerializationException, StateAccessException {
        return client.poolMemberCreate(getStockPoolMember(poolId));
    }

    protected VIP getStockVip(UUID poolId) {
        return new VIP()
                .setAddress("192.168.100.1")
                .setPoolId(poolId)
                .setProtocolPort(80)
                .setSessionPersistence("SOURCE_IP");
    }

    protected UUID createStockVip(UUID poolId)
            throws SerializationException, StateAccessException {
        return client.vipCreate(getStockVip(poolId));
    }
}

