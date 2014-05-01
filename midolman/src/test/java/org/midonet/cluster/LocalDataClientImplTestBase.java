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
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.dhcp.Subnet;
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
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.config.TypedConfigModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.l4lb.PoolLBMethod;
import org.midonet.midolman.state.l4lb.PoolProtocol;
import org.midonet.midolman.state.l4lb.VipSessionPersistence;
import org.midonet.midolman.state.zkManagers.*;
import org.midonet.midolman.state.l4lb.MappingStatusException;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.packets.IPv4Subnet;

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

    BridgeDhcpZkManager getBridgeDhcpZkManager() {
        return injector.getInstance(BridgeDhcpZkManager.class);
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
                new CacheModule(),
                new MockMonitoringStoreModule(),
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
        return new Subnet()
                .setSubnetAddr(new IPv4Subnet(cidr));
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
                .setLbMethod(PoolLBMethod.ROUND_ROBIN)
                .setProtocol(PoolProtocol.TCP);
    }

    protected UUID createStockPool(UUID loadBalancerId)
            throws MappingStatusException, SerializationException,
            StateAccessException {
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
            throws MappingStatusException, SerializationException,
            StateAccessException {
        return client.poolMemberCreate(getStockPoolMember(poolId));
    }

    protected VIP getStockVip(UUID poolId) {
        return new VIP()
                .setAddress("192.168.100.1")
                .setPoolId(poolId)
                .setProtocolPort(80)
                .setSessionPersistence(VipSessionPersistence.SOURCE_IP);
    }

    protected UUID createStockVip(UUID poolId)
            throws MappingStatusException, SerializationException,
            StateAccessException {
        return client.vipCreate(getStockVip(poolId));
    }
}

