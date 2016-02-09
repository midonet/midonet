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

package org.midonet.cluster;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import scala.Option;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;

import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.client.ArpCache;
import org.midonet.cluster.client.BridgeBuilder;
import org.midonet.cluster.client.ChainBuilder;
import org.midonet.cluster.client.IPAddrGroupBuilder;
import org.midonet.cluster.client.IpMacMap;
import org.midonet.cluster.client.LoadBalancerBuilder;
import org.midonet.cluster.client.MacLearningTable;
import org.midonet.cluster.client.PoolBuilder;
import org.midonet.cluster.client.PortBuilder;
import org.midonet.cluster.client.PortGroupBuilder;
import org.midonet.cluster.client.RouterBuilder;
import org.midonet.cluster.client.VlanPortMap;
import org.midonet.cluster.data.PortActiveTunnelKey;
import org.midonet.cluster.data.PortGroup;
import org.midonet.cluster.data.l4lb.Pool;
import org.midonet.cluster.data.l4lb.PoolMember;
import org.midonet.cluster.data.l4lb.VIP;
import org.midonet.cluster.storage.MidonetBackendTestModule;
import org.midonet.conf.MidoTestConfigurator;
import org.midonet.midolman.Setup;
import org.midonet.midolman.cluster.LegacyClusterModule;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.cluster.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.guice.config.MidolmanConfigModule;
import org.midonet.midolman.layer3.Route;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.LiteralRule;
import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.rules.RuleResult;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.ArpCacheEntry;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortDirectory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.VipSessionPersistence;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager.ChainConfig;
import org.midonet.midolman.state.zkManagers.IpAddrGroupZkManager;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager;
import org.midonet.midolman.state.zkManagers.PoolZkManager;
import org.midonet.midolman.state.zkManagers.PortGroupZkManager;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.state.zkManagers.RuleZkManager;
import org.midonet.midolman.state.zkManagers.VipZkManager;
import org.midonet.midolman.topology.devices.Port;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.util.functors.Callback3;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LocalClientImplTest {

    @Inject
    Client client;
    Injector injector = null;
    String zkRoot = "/test/v3/midolman";


    Config fillConfig() {
        return ConfigFactory.empty().withValue("zookeeper.root_key",
                         ConfigValueFactory.fromAnyRef(zkRoot));
    }

    PortZkManager getPortZkManager() {
        return injector.getInstance(PortZkManager.class);
    }

    BridgeZkManager getBridgeZkManager() {
        return injector.getInstance(BridgeZkManager.class);
    }

    RouterZkManager getRouterZkManager() {
        return injector.getInstance(RouterZkManager.class);
    }

    ChainZkManager getChainZkManager() {
        return injector.getInstance(ChainZkManager.class);
    }

    RuleZkManager getRuleZkManager() {
        return injector.getInstance(RuleZkManager.class);
    }

    IpAddrGroupZkManager getIpAddrGroupZkManager() {
        return injector.getInstance(IpAddrGroupZkManager.class);
    }

    LoadBalancerZkManager getLoadBalancerZkManager() {
        return injector.getInstance(LoadBalancerZkManager.class);
    }

    VipZkManager getVipZkManager() {
        return injector.getInstance(VipZkManager.class);
    }

    PoolZkManager getPoolZkManager() {
        return injector.getInstance(PoolZkManager.class);
    }

    PoolMemberZkManager getPoolMemberZkManager() {
        return injector.getInstance(PoolMemberZkManager.class);
    }

    PortGroupZkManager getPortGroupZkManager() {
        return injector.getInstance(PortGroupZkManager.class);
    }

    private UUID getRandomChainId()
            throws StateAccessException, SerializationException {
        ChainConfig inChainConfig = new ChainConfig(
                UUID.randomUUID().toString());
        return getChainZkManager().create(inChainConfig);
    }

    Directory zkDir() {
        return injector.getInstance(Directory.class);
    }

    @Before
    public void initialize() {
        Config conf = MidoTestConfigurator.forAgents(fillConfig());
        injector = Guice.createInjector(
            new SerializationModule(),
            new MidolmanConfigModule(conf),
            new MockZookeeperConnectionModule(),
            new MidonetBackendTestModule(conf),
            new LegacyClusterModule()
        );
        injector.injectMembers(this);

    }

    @Test
    public void getPortTest()
            throws StateAccessException, InterruptedException, KeeperException,
            SerializationException, TimeoutException {

        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
        UUID bridgeId = getBridgeZkManager().create(
            new BridgeZkManager.BridgeConfig("test", null, null));
        UUID portId = getPortZkManager().create(
            new PortDirectory.BridgePortConfig(bridgeId, true));

        TestPortBuilder portBuilder = new TestPortBuilder();
        client.getPort(portId, portBuilder);

        // The port builder calls build twice: once when loading the port config
        // from ZK, and a second time when loading the active field via
        // `onNewBuilder`.
        portBuilder.awaitBuildCalls(2, 5, TimeUnit.SECONDS);
        assertThat("Build is called", portBuilder.getBuildCallsCount(),
                   equalTo(2));
        Assert.assertThat(portBuilder.port.id(), equalTo(portId));
        Assert.assertThat(portBuilder.port.adminStateUp(), equalTo(true));
        Assert.assertThat(portBuilder.isActive(), equalTo(false));

        // Update the port configuration.
        getPortZkManager().update(
            portId, new PortDirectory.BridgePortConfig(bridgeId, false));

        portBuilder.awaitBuildCalls(3, 5, TimeUnit.SECONDS);
        assertThat("Build is called", portBuilder.getBuildCallsCount(),
                   equalTo(3));
        Assert.assertThat(portBuilder.port.id(), equalTo(portId));
        Assert.assertThat(portBuilder.port.adminStateUp(), equalTo(false));
        Assert.assertThat(portBuilder.isActive(), equalTo(false));

        // Set the port as active.
        UUID hostId = UUID.randomUUID();
        getPortZkManager().setActivePort(portId, hostId, true, 1L)
                          .toBlocking().first();

        portBuilder.awaitBuildCalls(4, 5, TimeUnit.SECONDS);
        assertThat("Build is called", portBuilder.getBuildCallsCount(),
                   equalTo(4));
        Assert.assertThat(portBuilder.port.id(), equalTo(portId));
        Assert.assertThat(portBuilder.port.adminStateUp(), equalTo(false));
        Assert.assertThat(portBuilder.isActive(), equalTo(true));

        // Set the port as inactive.
        getPortZkManager().setActivePort(portId, hostId, false, 1L)
                          .toBlocking().first();

        portBuilder.awaitBuildCalls(5, 5, TimeUnit.SECONDS);
        assertThat("Build is called", portBuilder.getBuildCallsCount(),
                   equalTo(5));
        Assert.assertThat(portBuilder.port.id(), equalTo(portId));
        Assert.assertThat(portBuilder.port.adminStateUp(), equalTo(false));
        Assert.assertThat(portBuilder.isActive(), equalTo(false));

        // Delete the port.
        getPortZkManager().delete(portId);

        portBuilder.awaitDeleteCalls(1, 5, TimeUnit.SECONDS);
        // We may receive up to 4 additional calls to build because every we
        // change the children of the alive znode we add a new exists watcher on
        // it. However, when the watcher on the port znode triggers, we clear
        // the builder preventing additional calls to build. Because the order
        // of watchers is not deterministic in the MockDirectory (hash set) we
        // cannot predict how many calls to build we receive.
        int buildCalls = portBuilder.getBuildCallsCount();
        assertThat("Build is called", buildCalls, lessThanOrEqualTo(9));
        assertThat("Delete is called", portBuilder.getDeletedCallsCount(),
                   equalTo(1));

        // Re-create the port does not trigger further updates on this builder.
        PortConfig portConfig =
            new PortDirectory.BridgePortConfig(bridgeId, true);
        portConfig.id = portId;
        getPortZkManager().create(portConfig);

        assertThat("Build is called", portBuilder.getBuildCallsCount(),
                   equalTo(buildCalls));

        // Delete the port does not trigger further updates on this builder.
        getPortZkManager().delete(portId);

        assertThat("Delete is called", portBuilder.getDeletedCallsCount(),
                   equalTo(1));
    }

    @Test
    public void getPortGroupTest()
        throws StateAccessException, InterruptedException, KeeperException,
               SerializationException, TimeoutException {

        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);

        UUID portGroupId = getPortGroupZkManager().create(
            new PortGroupZkManager.PortGroupConfig("test1", false));

        TestPortGroupBuilder portGroupBuilder = new TestPortGroupBuilder();
        client.getPortGroup(portGroupId, portGroupBuilder);

        // The port group builder calls build twice: once when loading the port
        // group config from ZK, and a second time when loading the group
        // members.
        portGroupBuilder.awaitBuildCalls(2, 5, TimeUnit.SECONDS);
        assertThat("Build is called", portGroupBuilder.getBuildCallsCount(),
                   equalTo(2));
        assertThat(portGroupBuilder.portGroup.getId(), equalTo(portGroupId));
        assertThat(portGroupBuilder.portGroup.getName(), equalTo("test1"));
        assertThat(portGroupBuilder.portGroup.isStateful(), equalTo(false));
        assertThat(portGroupBuilder.members.size(), equalTo(0));

        // Update the port group configuration.
        getPortGroupZkManager().update(
            portGroupId, new PortGroupZkManager.PortGroupConfig("test2", true));

        portGroupBuilder.awaitBuildCalls(4, 5, TimeUnit.SECONDS);
        assertThat("Build is called", portGroupBuilder.getBuildCallsCount(),
                   equalTo(4));
        assertThat(portGroupBuilder.portGroup.getId(), equalTo(portGroupId));
        assertThat(portGroupBuilder.portGroup.getName(), equalTo("test2"));
        assertThat(portGroupBuilder.portGroup.isStateful(), equalTo(true));
        assertThat(portGroupBuilder.members.size(), equalTo(0));

        // Add port to port group.
        UUID bridgeId = getBridgeZkManager().create(
            new BridgeZkManager.BridgeConfig("test", null, null));
        UUID portId = getPortZkManager().create(
            new PortDirectory.BridgePortConfig(bridgeId, true));

        getPortGroupZkManager().addPortToPortGroup(portGroupId, portId);

        portGroupBuilder.awaitBuildCalls(6, 5, TimeUnit.SECONDS);
        assertThat("Build is called", portGroupBuilder.getBuildCallsCount(),
                   equalTo(6));
        assertThat(portGroupBuilder.portGroup.getId(), equalTo(portGroupId));
        assertThat(portGroupBuilder.portGroup.getName(), equalTo("test2"));
        assertThat(portGroupBuilder.portGroup.isStateful(), equalTo(true));
        assertThat(portGroupBuilder.members.size(), equalTo(1));
        assertThat(portGroupBuilder.members.contains(portId), equalTo(true));

        // Remove port from port group.
        getPortGroupZkManager().removePortFromPortGroup(portGroupId, portId);

        portGroupBuilder.awaitBuildCalls(8, 5, TimeUnit.SECONDS);
        assertThat("Build is called", portGroupBuilder.getBuildCallsCount(),
                   equalTo(8));
        assertThat(portGroupBuilder.portGroup.getId(), equalTo(portGroupId));
        assertThat(portGroupBuilder.portGroup.getName(), equalTo("test2"));
        assertThat(portGroupBuilder.portGroup.isStateful(), equalTo(true));
        assertThat(portGroupBuilder.members.size(), equalTo(0));

        // Delete the port group.
        getPortGroupZkManager().delete(portGroupId);

        portGroupBuilder.awaitDeleteCalls(1, 5, TimeUnit.SECONDS);

        // Re-create the port group does not trigger further updates on this
        // builder.
        int buildCalls = portGroupBuilder.getBuildCallsCount();
        PortGroupZkManager.PortGroupConfig portGroupConfig =
            new PortGroupZkManager.PortGroupConfig("test3", false);
        portGroupConfig.id = portGroupId;
        getPortGroupZkManager().create(portGroupConfig);

        assertThat("Build is called", portGroupBuilder.getBuildCallsCount(),
                   equalTo(buildCalls));

        // Delete the port group does not trigger further updates on this
        // builder.
        getPortGroupZkManager().delete(portGroupId);

        assertThat("Delete is called", portGroupBuilder.getDeletedCallsCount(),
                   equalTo(1));
    }

    @Test
    public void getBridgeTest()
            throws StateAccessException, InterruptedException, KeeperException,
            SerializationException, BridgeZkManager.VxLanPortIdUpdateException,
            TimeoutException {

        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
        UUID bridgeId = getBridgeZkManager().create(
            new BridgeZkManager.BridgeConfig("test", null, null));
        TestBridgeBuilder bridgeBuilder = new TestBridgeBuilder();
        client.getBridge(bridgeId, bridgeBuilder);

        bridgeBuilder.awaitBuildCalls(1, 5, TimeUnit.SECONDS);
        assertThat("Build is called", bridgeBuilder.getBuildCallsCount(),
                   equalTo(1));

        // Update the bridge.
        getBridgeZkManager().update(
            bridgeId, new BridgeZkManager.BridgeConfig(
                "test1", getRandomChainId(), getRandomChainId()));

        bridgeBuilder.awaitBuildCalls(2, 5, TimeUnit.SECONDS);
        assertThat("Bridge update was notified",
                   bridgeBuilder.getBuildCallsCount(), equalTo(2));

        // Delete the bridge.
        getBridgeZkManager().delete(bridgeId);

        bridgeBuilder.awaitDeleteCalls(1, 5, TimeUnit.SECONDS);

        // Re-create the bridge does not trigger further updates on this
        // builder.
        int buildCalls = bridgeBuilder.getBuildCallsCount();
        BridgeZkManager.BridgeConfig bridgeConfig =
            new BridgeZkManager.BridgeConfig("test", null, null);
        bridgeConfig.id = bridgeId;
        getBridgeZkManager().create(bridgeConfig);

        assertThat("Build is called", bridgeBuilder.getBuildCallsCount(),
                   equalTo(buildCalls));

        // Delete the bridge does not trigger further updates on this builder.
        getBridgeZkManager().delete(bridgeId);

        assertThat("Delete is called", bridgeBuilder.getDeletedCallsCount(),
                   equalTo(1));
    }

    @Test
    public void getRouterTest()
            throws StateAccessException, InterruptedException, KeeperException,
            SerializationException, TimeoutException {

        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
        UUID routerId = getRouterZkManager().create();
        TestRouterBuilder routerBuilder = new TestRouterBuilder();
        client.getRouter(routerId, routerBuilder);

        routerBuilder.awaitBuildCalls(1, 5, TimeUnit.SECONDS);
        assertThat("Build is called", routerBuilder.getBuildCallsCount(),
                   equalTo(1));

        // Update the router.
        getRouterZkManager().update(
            routerId, new RouterZkManager.RouterConfig(
                "test1", getRandomChainId(), getRandomChainId(), null));

        routerBuilder.awaitBuildCalls(2, 5, TimeUnit.SECONDS);
        assertThat("Router update was notified",
                   routerBuilder.getBuildCallsCount(), equalTo(2));

        // Delete the router.
        getRouterZkManager().delete(routerId);

        routerBuilder.awaitDeleteCalls(1, 5, TimeUnit.SECONDS);

        // Re-create the router does not trigger further updates on this
        // builder.
        int buildCalls = routerBuilder.getBuildCallsCount();
        RouterZkManager.RouterConfig routerConfig =
            new RouterZkManager.RouterConfig("test", getRandomChainId(),
                                             getRandomChainId(), null);
        routerConfig.id = routerId;
        getRouterZkManager().create(routerConfig);

        assertThat("Build is called", routerBuilder.getBuildCallsCount(),
                   equalTo(buildCalls));

        // Delete the router does not trigger further updates on this builder.
        getRouterZkManager().delete(routerId);

        assertThat("Delete is called", routerBuilder.getDeletedCallsCount(),
                   equalTo(1));
    }

    @Test
    public void getChainTest()
            throws StateAccessException, InterruptedException, KeeperException,
                   SerializationException,
                   org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException,
                   TimeoutException {

        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);

        UUID chainId = getChainZkManager().create(new ChainConfig("test1"));

        TestChainBuilder chainBuilder = new TestChainBuilder();
        client.getChain(chainId, chainBuilder);

        // Build is called twice: once for the chain name and once for the chain
        // rules.
        chainBuilder.awaitBuildCalls(2, 5, TimeUnit.SECONDS);
        assertThat("Build is called", chainBuilder.getBuildCallsCount(),
                   equalTo(2));
        assertThat(chainBuilder.name, equalTo("test1"));
        assertThat(chainBuilder.rules.size(), equalTo(0));

        // Update the chain name.
        getChainZkManager().update(chainId, new ChainConfig("test2"));

        chainBuilder.awaitBuildCalls(3, 5, TimeUnit.SECONDS);
        assertThat("Build is called", chainBuilder.getBuildCallsCount(),
                   equalTo(3));
        assertThat(chainBuilder.name, equalTo("test2"));

        // Add a rule to the chain.
        Rule rule = new LiteralRule(new Condition(), RuleResult.Action.ACCEPT,
                                    chainId, 1);
        UUID ruleId = getRuleZkManager().create(null, rule, 1);

        chainBuilder.awaitBuildCalls(4, 5, TimeUnit.SECONDS);
        assertThat("Build is called", chainBuilder.getBuildCallsCount(),
                   equalTo(4));
        assertThat(chainBuilder.rules.size(), equalTo(1));
        assertThat(chainBuilder.rules.get(0).id, equalTo(ruleId));

        // Delete a rule from the chain.
        getRuleZkManager().delete(ruleId);

        chainBuilder.awaitBuildCalls(5, 5, TimeUnit.SECONDS);
        assertThat("Build is called", chainBuilder.getBuildCallsCount(),
                   equalTo(5));
        assertThat(chainBuilder.rules.size(), equalTo(0));

        // Delete the chain.
        getChainZkManager().delete(chainId);

        chainBuilder.awaitDeleteCalls(1, 5, TimeUnit.SECONDS);

        // Re-create the chain does not trigger further updates on this builder.
        int buildCalls = chainBuilder.getBuildCallsCount();

        ChainConfig chainConfig = new ChainConfig("test1");
        chainConfig.id = chainId;
        getChainZkManager().create(chainConfig);

        assertThat("Build is called", chainBuilder.getBuildCallsCount(),
                   equalTo(buildCalls));

        // Delete the chain does not trigger further updates on this builder.
        getChainZkManager().delete(chainId);

        assertThat("Delete is called", chainBuilder.getDeletedCallsCount(),
                   equalTo(1));
    }

    @Test
    public void getIpAddrGroupTest()
            throws StateAccessException, InterruptedException, KeeperException,
                   SerializationException, TimeoutException {

        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);

        UUID ipAddrGroupId = getIpAddrGroupZkManager().create(
            new IpAddrGroupZkManager.IpAddrGroupConfig("test"));

        TestIpAddrGroupBuilder ipAddrGroupBuilder = new TestIpAddrGroupBuilder();
        client.getIPAddrGroup(ipAddrGroupId, ipAddrGroupBuilder);

        ipAddrGroupBuilder.awaitBuildCalls(1, 5, TimeUnit.SECONDS);
        assertThat("Build is called", ipAddrGroupBuilder.getBuildCallsCount(),
                   equalTo(1));
        assertThat(ipAddrGroupBuilder.addrs.size(), equalTo(0));

        // Add an IP address to the group.
        getIpAddrGroupZkManager().addAddr(ipAddrGroupId, "1.2.3.4");

        ipAddrGroupBuilder.awaitBuildCalls(2, 5, TimeUnit.SECONDS);
        assertThat("Build is called", ipAddrGroupBuilder.getBuildCallsCount(),
                   equalTo(2));
        assertThat(ipAddrGroupBuilder.addrs.size(), equalTo(1));
        assertThat(ipAddrGroupBuilder.addrs.contains(IPv4Addr.apply("1.2.3.4")),
                   equalTo(true));

        // Remove an IP address from the group.
        getIpAddrGroupZkManager().removeAddr(ipAddrGroupId, "1.2.3.4");

        ipAddrGroupBuilder.awaitBuildCalls(3, 5, TimeUnit.SECONDS);
        assertThat("Build is called", ipAddrGroupBuilder.getBuildCallsCount(),
                   equalTo(3));
        assertThat(ipAddrGroupBuilder.addrs.size(), equalTo(0));

        // Delete the group.
        getIpAddrGroupZkManager().delete(ipAddrGroupId);

        ipAddrGroupBuilder.awaitDeleteCalls(1, 5, TimeUnit.SECONDS);

        // Re-create the group does not trigger further updates on this builder.
        int buildCalls = ipAddrGroupBuilder.getBuildCallsCount();

        IpAddrGroupZkManager.IpAddrGroupConfig ipAddrGroupConfig =
            new IpAddrGroupZkManager.IpAddrGroupConfig(ipAddrGroupId, "test");
        getIpAddrGroupZkManager().create(ipAddrGroupConfig);

        assertThat("Build is called", ipAddrGroupBuilder.getBuildCallsCount(),
                   equalTo(buildCalls));

        // Delete the group does not trigger further updates on this builder.
        getIpAddrGroupZkManager().delete(ipAddrGroupId);

        assertThat("Delete is called", ipAddrGroupBuilder.getDeletedCallsCount(),
                   equalTo(1));
    }

    @Test
    public void getLoadBalancerTest()
            throws StateAccessException, InterruptedException, KeeperException,
                   SerializationException, InvalidStateOperationException,
                   TimeoutException {

        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);

        UUID routerId = UUID.randomUUID();
        UUID loadBalancerId = UUID.randomUUID();
        LoadBalancerZkManager.LoadBalancerConfig loadBalancerConfig =
            new LoadBalancerZkManager.LoadBalancerConfig(routerId, false);

        getLoadBalancerZkManager().create(loadBalancerId, loadBalancerConfig);

        TestLoadBalancerBuilder loadBalancerBuilder = new TestLoadBalancerBuilder();
        client.getLoadBalancer(loadBalancerId, loadBalancerBuilder);

        loadBalancerBuilder.awaitBuildCalls(4, 5, TimeUnit.SECONDS);
        assertThat("Build is called", loadBalancerBuilder.getBuildCallsCount(),
                   equalTo(4));
        assertThat(loadBalancerBuilder.adminStateUp, equalTo(false));
        assertThat(loadBalancerBuilder.routerId, equalTo(routerId));
        assertThat(loadBalancerBuilder.vips.size(), equalTo(0));

        // Update the admin state.
        loadBalancerConfig.adminStateUp = true;
        getLoadBalancerZkManager().update(loadBalancerId, loadBalancerConfig);

        loadBalancerBuilder.awaitBuildCalls(7, 5, TimeUnit.SECONDS);
        assertThat("Build is called", loadBalancerBuilder.getBuildCallsCount(),
                   equalTo(7));
        assertThat(loadBalancerBuilder.adminStateUp, equalTo(true));

        // Add a VIP.
        UUID vipId = UUID.randomUUID();
        VipZkManager.VipConfig vipConfig = new VipZkManager.VipConfig(
            loadBalancerId, null, "1.2.3.4", 1000,
            VipSessionPersistence.SOURCE_IP, true);
        zkDir().multi(getVipZkManager()
                          .prepareCreate(vipId, vipConfig));
        zkDir().multi(getLoadBalancerZkManager()
                          .prepareAddVip(loadBalancerId, vipId));

        loadBalancerBuilder.awaitBuildCalls(8, 5, TimeUnit.SECONDS);
        assertThat("Build is called", loadBalancerBuilder.getBuildCallsCount(),
                   equalTo(8));
        assertThat(loadBalancerBuilder.vips.size(), equalTo(1));
        assertThat(loadBalancerBuilder.vips.get(vipId).getAddress(),
                   equalTo("1.2.3.4"));

        // Update a VIP.
        vipConfig.address = "5.6.7.8";
        zkDir().multi(getVipZkManager().prepareUpdate(vipId, vipConfig));

        loadBalancerBuilder.awaitBuildCalls(9, 5, TimeUnit.SECONDS);
        assertThat("Build is called", loadBalancerBuilder.getBuildCallsCount(),
                   equalTo(9));
        assertThat(loadBalancerBuilder.vips.size(), equalTo(1));
        assertThat(loadBalancerBuilder.vips.get(vipId).getAddress(),
                   equalTo("5.6.7.8"));

        // Remove a VIP.
        zkDir().multi(getLoadBalancerZkManager()
                          .prepareRemoveVip(loadBalancerId, vipId));

        loadBalancerBuilder.awaitBuildCalls(10, 5, TimeUnit.SECONDS);
        assertThat("Build is called", loadBalancerBuilder.getBuildCallsCount(),
                   equalTo(10));
        assertThat(loadBalancerBuilder.vips.size(), equalTo(0));

        // Delete the load balancer.
        zkDir().multi(getLoadBalancerZkManager().prepareDelete(loadBalancerId));

        loadBalancerBuilder.awaitDeleteCalls(1, 5, TimeUnit.SECONDS);

        // Re-create the load balancer does not trigger further updates on this
        // builder.
        int buildCalls = loadBalancerBuilder.getBuildCallsCount();
        getLoadBalancerZkManager().create(loadBalancerId, loadBalancerConfig);

        assertThat("Build is called", loadBalancerBuilder.getBuildCallsCount(),
                   equalTo(buildCalls));

        // Delete the load balancer does not trigger further updates on this
        // builder.
        zkDir().multi(getLoadBalancerZkManager().prepareDelete(loadBalancerId));

        assertThat("Delete is called", loadBalancerBuilder.getDeletedCallsCount(),
                   equalTo(1));
    }

    @Test
    public void getPoolTest()
        throws StateAccessException, InterruptedException, KeeperException,
               SerializationException, InvalidStateOperationException,
               TimeoutException {

        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);

        UUID poolId = UUID.randomUUID();
        PoolZkManager.PoolConfig poolConfig =
            new PoolZkManager.PoolConfig();
        zkDir().multi(getPoolZkManager().prepareCreate(poolId, poolConfig));

        TestPoolBuilder poolBuilder = new TestPoolBuilder();
        client.getPool(poolId, poolBuilder);

        poolBuilder.awaitBuildCalls(2, 5, TimeUnit.SECONDS);
        assertThat("Build is called", poolBuilder.getBuildCallsCount(),
                   equalTo(2));
        assertThat(poolBuilder.pool.getId(), equalTo(poolId));
        assertThat(poolBuilder.members.size(), equalTo(0));

        // Update the pool.
        poolConfig.adminStateUp = true;
        zkDir().multi(getPoolZkManager().prepareUpdate(poolId, poolConfig));

        poolBuilder.awaitBuildCalls(3, 5, TimeUnit.SECONDS);
        assertThat("Build is called", poolBuilder.getBuildCallsCount(),
                   equalTo(3));
        assertThat(poolBuilder.pool.isAdminStateUp(), equalTo(true));

        // Add member to the pool.
        UUID memberId = UUID.randomUUID();
        PoolMemberZkManager.PoolMemberConfig memberConfig =
            new PoolMemberZkManager.PoolMemberConfig(
                poolId, "1.2.3.4", 80, 0, true, LBStatus.ACTIVE);
        zkDir().multi(getPoolMemberZkManager()
                          .prepareCreate(memberId, memberConfig));
        zkDir().multi(getPoolZkManager().prepareAddMember(poolId, memberId));

        poolBuilder.awaitBuildCalls(4, 5, TimeUnit.SECONDS);
        assertThat("Build is called", poolBuilder.getBuildCallsCount(),
                   equalTo(4));
        assertThat(poolBuilder.members.size(), equalTo(1));
        assertThat(poolBuilder.members.get(memberId).getAddress(),
                   equalTo("1.2.3.4"));

        // Update a pool member.
        memberConfig.address = "5.6.7.8";
        zkDir().multi(getPoolMemberZkManager()
                          .prepareUpdate(memberId, memberConfig));

        poolBuilder.awaitBuildCalls(5, 5, TimeUnit.SECONDS);
        assertThat("Build is called", poolBuilder.getBuildCallsCount(),
                   equalTo(5));
        assertThat(poolBuilder.members.size(), equalTo(1));
        assertThat(poolBuilder.members.get(memberId).getAddress(),
                   equalTo("5.6.7.8"));

        // Remove a member.
        zkDir().multi(getPoolZkManager().prepareRemoveMember(poolId, memberId));

        poolBuilder.awaitBuildCalls(6, 5, TimeUnit.SECONDS);
        assertThat("Build is called", poolBuilder.getBuildCallsCount(),
                   equalTo(6));
        assertThat(poolBuilder.members.size(), equalTo(0));

        // Delete a pool.
        zkDir().multi(getPoolZkManager().prepareDelete(poolId));

        poolBuilder.awaitDeleteCalls(1, 5, TimeUnit.SECONDS);

        // Re-create the pool does not trigger further updates on this builder.
        int buildCalls = poolBuilder.getBuildCallsCount();
        zkDir().multi(getPoolZkManager().prepareCreate(poolId, poolConfig));

        assertThat("Build is called", poolBuilder.getBuildCallsCount(),
                   equalTo(buildCalls));

        // Delete the pool does not trigger further updates on this builder.
        zkDir().multi(getPoolZkManager().prepareDelete(poolId));

        assertThat("Delete is called", poolBuilder.getDeletedCallsCount(),
                   equalTo(1));
    }

    @Test
    public void arpCacheTest() throws InterruptedException, KeeperException,
            StateAccessException, SerializationException, TimeoutException {
        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
        UUID routerId = getRouterZkManager().create();
        TestRouterBuilder routerBuilder = new TestRouterBuilder();
        client.getRouter(routerId, routerBuilder);

        routerBuilder.awaitBuildCalls(1, 5, TimeUnit.SECONDS);
        assertThat("Build is called", routerBuilder.getBuildCallsCount(),
                   equalTo(1));

        IPv4Addr ipAddr = IPv4Addr.fromString("192.168.0.0");
        ArpCacheEntry arpEntry = new ArpCacheEntry(MAC.random(), 0, 0, 0);
        // add an entry in the arp cache.
        routerBuilder.addNewArpEntry(ipAddr, arpEntry);

        routerBuilder.awaitBuildCalls(1, 5, TimeUnit.SECONDS);
        assertEquals(arpEntry, routerBuilder.getArpEntryForIp(ipAddr));
        assertThat("Router update was notified",
                   routerBuilder.getBuildCallsCount(), equalTo(1));
    }

    @Test
    public void macPortMapTest() throws InterruptedException,
            KeeperException, SerializationException, StateAccessException,
            TimeoutException {
        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
        UUID bridgeId = getBridgeZkManager().create(
            new BridgeZkManager.BridgeConfig("test", getRandomChainId(),
                    getRandomChainId()));
        TestBridgeBuilder bridgeBuilder = new TestBridgeBuilder();
        client.getBridge(bridgeId, bridgeBuilder);

        bridgeBuilder.awaitBuildCalls(1, 5, TimeUnit.SECONDS);
        assertThat("Build is called",
                   bridgeBuilder.getBuildCallsCount(), equalTo(1));

        // and a new packet.
        MAC mac = MAC.random();
        UUID portUUID = UUID.randomUUID();

        bridgeBuilder.simulateNewPacket(mac, portUUID);

        // make sure the  notifications sent what we expected.
        assertEquals(bridgeBuilder.getNotifiedMAC(), mac);
        assertNull(bridgeBuilder.getNotifiedUUID()[0]);
        assertEquals(portUUID, bridgeBuilder.getNotifiedUUID()[1]);

        // make sure the packet is there.
        assertEquals(portUUID, bridgeBuilder.getPort(mac));

        // remove the port.
        bridgeBuilder.removePort(mac, portUUID);

        // make sure the notifications sent what we expected.
        assertEquals(bridgeBuilder.getNotifiedMAC(), mac);
        assertEquals(portUUID, bridgeBuilder.getNotifiedUUID()[0]);
        assertNull(bridgeBuilder.getNotifiedUUID()[1]);

        // make sure that the mac <-> port association has been removed.
        assertNull(bridgeBuilder.getPort(mac));

        assertThat("Bridge update was notified",
                   bridgeBuilder.getBuildCallsCount(), equalTo(1));
    }

    static class AwaitableBuilder {

        private volatile Thread thread = null;
        private volatile int awaiting = 0;
        private AtomicInteger buildCallsCount = new AtomicInteger();
        private AtomicInteger deleteCallsCount = new AtomicInteger();

        public int getBuildCallsCount() {
            return buildCallsCount.get();
        }

        public int getDeletedCallsCount() {
            return deleteCallsCount.get();
        }

        public void incrementBuild() {
            increment(buildCallsCount);
        }

        public void incrementDelete() {
            increment(deleteCallsCount);
        }

        public void awaitBuildCalls(int expected, long timeout, TimeUnit unit)
            throws TimeoutException {
            await(buildCallsCount, expected, timeout, unit);
        }

        public void awaitDeleteCalls(int expected, long timeout, TimeUnit unit)
            throws TimeoutException {
            await(deleteCallsCount, expected, timeout, unit);
        }

        private void increment(AtomicInteger counter) {
            if (counter.incrementAndGet() >= awaiting) {
                wakeUp();
            }
        }

        private void await(AtomicInteger counter, int expected, long timeout,
                           TimeUnit unit) throws TimeoutException {
            long toWait = unit.toNanos(timeout);
            thread = Thread.currentThread();
            awaiting = expected;
            try {
                do {
                    if (counter.get() >= expected)
                        return;

                    if (toWait < 0)
                        throw new TimeoutException();

                    long start = System.nanoTime();
                    LockSupport.parkNanos(toWait);
                    toWait -= System.nanoTime() - start;
                } while (true);
            } finally {
                awaiting = 0;
                thread = null;
            }
        }

        private void wakeUp() {
            if (null != thread) {
                LockSupport.unpark(thread);
            }
        }

    }

    static class TestPortBuilder extends AwaitableBuilder implements PortBuilder {

        Port port = null;
        PortActiveTunnelKey patk = null;

        boolean isActive() {
            return patk != null;
        }

        @Override
        public void setPort(Port p) {
            port = p;
        }

        @Override
        public void setActive(PortActiveTunnelKey patk) {
            this.patk = patk;
        }

        @Override
        public void build() {
            incrementBuild();
        }

        @Override
        public void deleted() {
            incrementDelete();
        }

    }

    static class TestPortGroupBuilder extends AwaitableBuilder
        implements PortGroupBuilder {

        PortGroup portGroup = null;
        Set<UUID> members = null;

        @Override
        public void setConfig(PortGroup portGroup) {
            this.portGroup = portGroup;
            incrementBuild();
        }

        @Override
        public void setMembers(Set<UUID> members) {
            this.members = members;
            incrementBuild();
        }

        @Override
        public void build() {
            incrementBuild();
        }

        @Override
        public void deleted() {
            incrementDelete();
        }
    }

    static class TestChainBuilder extends AwaitableBuilder
        implements ChainBuilder {

        List<Rule> rules = null;
        String name = null;

        @Override
        public void setRules(List<Rule> rules) {
            this.rules = rules;
            incrementBuild();
        }

        @Override
        public void setRules(List<UUID> ruleOrder, Map<UUID, Rule> rules) {
            this.rules = new ArrayList<>();
            for (UUID id : ruleOrder) {
                this.rules.add(rules.get(id));
            }
            incrementBuild();
        }

        @Override
        public void setName(String name) {
            this.name = name;
            incrementBuild();
        }

        @Override
        public void build() {
            incrementBuild();
        }

        @Override
        public void deleted() {
            incrementDelete();
        }
    }

    static class TestIpAddrGroupBuilder extends AwaitableBuilder
        implements IPAddrGroupBuilder {

        Set<IPAddr> addrs = null;

        @Override
        public void setAddrs(Set<IPAddr> addrs) {
            this.addrs = addrs;
            incrementBuild();
        }

        @Override
        public void build() {
            incrementBuild();
        }

        @Override
        public void deleted() {
            incrementDelete();
        }
    }

    static class TestLoadBalancerBuilder extends AwaitableBuilder
        implements LoadBalancerBuilder {

        Boolean adminStateUp;
        UUID routerId;
        Map<UUID, VIP> vips;

        @Override
        public void setAdminStateUp(boolean adminStateUp) {
            this.adminStateUp = adminStateUp;
            incrementBuild();
        }

        @Override
        public void setRouterId(UUID routerId) {
            this.routerId = routerId;
            incrementBuild();
        }

        @Override
        public void setVips(Map<UUID, VIP> vips) {
            this.vips = vips;
            incrementBuild();
        }

        @Override
        public void build() {
            incrementBuild();
        }

        @Override
        public void deleted() {
            incrementDelete();
        }

    }

    static class TestPoolBuilder extends AwaitableBuilder
        implements PoolBuilder {

        Pool pool;
        Map<UUID, PoolMember> members;

        @Override
        public void setPoolConfig(Pool pool) {
            this.pool = pool;
            incrementBuild();
        }

        @Override
        public void setPoolMembers(Map<UUID, PoolMember> members) {
            this.members = members;
            incrementBuild();
        }

        @Override
        public void build() {
            incrementBuild();
        }

        @Override
        public void deleted() {
            incrementDelete();
        }

    }

    // hint could modify this class so we can get the map from it.
    static class TestBridgeBuilder extends AwaitableBuilder implements BridgeBuilder {
        Option<UUID> vlanBridgePeerPortId = Option.apply(null);
        List<UUID> exteriorVxlanPortIds = new ArrayList<>(0);
        MacLearningTable mlTable;
        IpMacMap<IPv4Addr> ipMacMap;
        MAC[] notifiedMAC = new MAC[1];
        UUID[] notifiedUUID = new UUID[2];
        VlanPortMap vlanPortMap = new VlanPortMapImpl();

        public void simulateNewPacket(MAC mac, UUID portId) {
            mlTable.add(mac, portId);
        }

        public void removePort(MAC mac, UUID portId) {
            mlTable.remove(mac, portId);
        }

        public UUID getPort(MAC mac) {
            final UUID result[] = new UUID[1];
            result[0] = mlTable.get(mac);
            return result[0];
        }

        public MAC getNotifiedMAC() {
            return notifiedMAC[0];
        }

        public UUID[] getNotifiedUUID() {
            return notifiedUUID;
        }

        @Override
        public BridgeBuilder setAdminStateUp(boolean adminStateUp) {
            return this;
        }

        @Override
        public void setTunnelKey(long key) {
        }

        @Override
        public void setExteriorPorts(List<UUID> ports) {
        }

        @Override
        public void removeMacLearningTable(short vlanId) {
        }

        @Override
        public Set<Short> vlansInMacLearningTable() {
            return new HashSet<>();
        }

        @Override
        public void setMacLearningTable(short vlanId, MacLearningTable table) {
            mlTable = table;
        }

        @Override
        public void setIp4MacMap(IpMacMap<IPv4Addr> map) {
            ipMacMap = map;
        }

        @Override
        public void setVlanPortMap(VlanPortMap map) {
            vlanPortMap = map;
        }

        @Override
        public void setLogicalPortsMap(Map<MAC, UUID> macToLogicalPortId,
                                       Map<IPAddr, MAC> ipToMac) {
        }

        @Override
        public void setVlanBridgePeerPortId(Option<UUID> id) {
            vlanBridgePeerPortId = id;
        }

        @Override
        public void updateMacEntry(
                short vlanId, MAC mac, UUID oldPort, UUID newPort) {
        }

        @Override
        public void setExteriorVxlanPortIds(List<UUID> ids) {
            exteriorVxlanPortIds = ids;
        }

        @Override
        public BridgeBuilder setInFilter(UUID filterID) {
            return this;
        }

        @Override
        public BridgeBuilder setOutFilter(UUID filterID) {
            return this;
        }

        @Override
        public void build() {
            // add the callback
            mlTable.notify(new Callback3<MAC,UUID,UUID>() {
                @Override
                public void call(MAC mac, UUID oldPortID, UUID newPortID) {
                    notifiedMAC[0] = mac;
                    notifiedUUID[0] = oldPortID;
                    notifiedUUID[1] = newPortID;
                }
            });
            incrementBuild();
        }

        @Override
        public void deleted() {
            incrementDelete();
        }

    }

    static class TestRouterBuilder extends AwaitableBuilder implements RouterBuilder {
        ArpCache arpCache;
        UUID loadBalancerId;

        public void addNewArpEntry(IPv4Addr ipAddr, ArpCacheEntry entry) {
            arpCache.add(ipAddr, entry);
        }

        public ArpCacheEntry getArpEntryForIp(IPv4Addr ipAddr) {
            return arpCache.get(ipAddr);
        }

        @Override
        public RouterBuilder setAdminStateUp(boolean adminStateUp) {
            return this;

        }

        @Override
        public RouterBuilder setInFilter(UUID filterID) {
            return this;
        }

        @Override
        public RouterBuilder setOutFilter(UUID filterID) {
            return this;
        }

        @Override
        public void setLoadBalancer(UUID lbID) {
            loadBalancerId = lbID;
        }

        @Override
        public void build() {
            incrementBuild();
        }

        @Override
        public void deleted() {
            incrementDelete();
        }

        @Override
        public void setArpCache(ArpCache table) {
           arpCache = table;
        }

        @Override
        public void addRoute(Route rt) {
            // TODO Auto-generated method stub

        }

        @Override
        public void removeRoute(Route rt) {
            // TODO Auto-generated method stub
        }
    }

}
