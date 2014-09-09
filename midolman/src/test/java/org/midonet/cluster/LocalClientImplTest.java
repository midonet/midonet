/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.cluster;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import scala.Option;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.client.ArpCache;
import org.midonet.cluster.client.BridgeBuilder;
import org.midonet.cluster.client.IpMacMap;
import org.midonet.cluster.client.MacLearningTable;
import org.midonet.cluster.client.RouterBuilder;
import org.midonet.cluster.client.VlanPortMap;
import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.midolman.Setup;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.cluster.ClusterClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.config.TypedConfigModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.layer3.Route;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.ArpCacheEntry;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager.ChainConfig;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.util.functors.Callback3;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

    RouterZkManager getRouterZkManager() {
        return injector.getInstance(RouterZkManager.class);
    }

    ChainZkManager getChainZkManager() {
        return injector.getInstance(ChainZkManager.class);
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
        HierarchicalConfiguration config = fillConfig(
            new HierarchicalConfiguration());
        injector = Guice.createInjector(
            new VersionModule(),
            new SerializationModule(),
            new ConfigProviderModule(config),
            new MockZookeeperConnectionModule(),
            new TypedConfigModule<>(MidolmanConfig.class),
            new ClusterClientModule()
        );
        injector.injectMembers(this);

    }

    @Test
    public void getBridgeTest()
            throws StateAccessException, InterruptedException, KeeperException,
            SerializationException, BridgeZkManager.VxLanPortIdUpdateException {

        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
        UUID bridgeId = getBridgeZkManager().create(
            new BridgeZkManager.BridgeConfig("test", null, null));
        TestBridgeBuilder bridgeBuilder = new TestBridgeBuilder();
        client.getBridge(bridgeId, bridgeBuilder);

        pollCallCounts(bridgeBuilder, 1);
        assertThat("Build is called", bridgeBuilder.getBuildCallsCount(),
                   equalTo(1));

        // let's cause a bridge update
        getBridgeZkManager().update(bridgeId,
                                    new BridgeZkManager.BridgeConfig("test1",
                                            getRandomChainId(),
                                            getRandomChainId()));

        pollCallCounts(bridgeBuilder, 2);
        assertThat("Bridge update was notified",
                   bridgeBuilder.getBuildCallsCount(), equalTo(2));

    }

    @Test
    public void getRouterTest()
            throws StateAccessException, InterruptedException, KeeperException,
            SerializationException {

        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
        UUID routerId = getRouterZkManager().create();
        TestRouterBuilder routerBuilder = new TestRouterBuilder();
        client.getRouter(routerId, routerBuilder);

        pollCallCounts(routerBuilder, 1);
        assertThat("Build is called", routerBuilder.getBuildCallsCount(),
                   equalTo(1));

        // let's cause a router update
        getRouterZkManager().update(routerId,
                                    new RouterZkManager.RouterConfig("test1",
                                            getRandomChainId(),
                                            getRandomChainId(),
                                            null));

        pollCallCounts(routerBuilder, 2);
        assertThat("Router update was notified",
                   routerBuilder.getBuildCallsCount(), equalTo(2));

    }

    @Test
    public void ArpCacheTest() throws InterruptedException, KeeperException,
            StateAccessException, SerializationException {
        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
        UUID routerId = getRouterZkManager().create();
        TestRouterBuilder routerBuilder = new TestRouterBuilder();
        client.getRouter(routerId, routerBuilder);

        pollCallCounts(routerBuilder, 1);
        assertThat("Build is called", routerBuilder.getBuildCallsCount(),
                   equalTo(1));

        IPv4Addr ipAddr = IPv4Addr.fromString("192.168.0.0");
        ArpCacheEntry arpEntry = new ArpCacheEntry(MAC.random(), 0, 0, 0);
        // add an entry in the arp cache.
        routerBuilder.addNewArpEntry(ipAddr, arpEntry);

        pollCallCounts(routerBuilder, 1);
        assertEquals(arpEntry, routerBuilder.getArpEntryForIp(ipAddr));
        assertThat("Router update was notified",
                   routerBuilder.getBuildCallsCount(), equalTo(1));

    }

    @Test
    public void MacPortMapTest() throws InterruptedException,
            KeeperException, SerializationException, StateAccessException {
        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
        UUID bridgeId = getBridgeZkManager().create(
            new BridgeZkManager.BridgeConfig("test", getRandomChainId(),
                    getRandomChainId()));
        TestBridgeBuilder bridgeBuilder = new TestBridgeBuilder();
        client.getBridge(bridgeId, bridgeBuilder);

        pollCallCounts(bridgeBuilder, 1);
        assertThat("Build is called", bridgeBuilder.getBuildCallsCount(), equalTo(1));

        // and a new packet.
        MAC mac = MAC.random();
        UUID portUUID = UUID.randomUUID();

        ///////////
        // This sends two notifications.
        bridgeBuilder.simulateNewPacket(mac,portUUID);
        ///////////

        pollCallCounts(bridgeBuilder, 3);

        // make sure the  notifications sent what we expected.
        assertEquals(bridgeBuilder.getNotifiedMAC(), mac);
        assertNull(bridgeBuilder.getNotifiedUUID()[0]);
        assertEquals(portUUID, bridgeBuilder.getNotifiedUUID()[1]);

        // make sure the packet is there.
        assertEquals(portUUID, bridgeBuilder.getPort(mac));

        // remove the port.
        bridgeBuilder.removePort(mac, portUUID);

        pollCallCounts(bridgeBuilder, 5);

        // make sure the notifications sent what we expected.
        assertEquals(bridgeBuilder.getNotifiedMAC(), mac);
        assertEquals(portUUID, bridgeBuilder.getNotifiedUUID()[0]);
        assertNull(bridgeBuilder.getNotifiedUUID()[1]);

        // make sure that the mac <-> port association has been removed.
        assertNull(bridgeBuilder.getPort(mac));

        assertThat("Bridge update was notified", bridgeBuilder.getBuildCallsCount(), equalTo(1));
    }

    // hint could modify this class so we can get the map from it.
    class TestBridgeBuilder implements BridgeBuilder, BuildCallCounter {
        int buildCallsCount = 0;
        Option<UUID> vlanBridgePeerPortId = Option.apply(null);
        Option<UUID> exteriorVxlanPortId = Option.apply(null);
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

        public int getBuildCallsCount() {
            return buildCallsCount;
        }

        @Override
        public BridgeBuilder setAdminStateUp(boolean adminStateUp) {
            return this;
        }

        @Override
        public void setTunnelKey(long key) {
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
        public void setExteriorVxlanPortId(Option<UUID> id) {
            exteriorVxlanPortId = id;
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
            buildCallsCount++;
            // add the callback
            mlTable.notify(new Callback3<MAC,UUID,UUID>() {
                @Override
                public void call(MAC mac, UUID oldPortID, UUID newPortID) {
                    notifiedMAC[0] = mac;
                    notifiedUUID[0] = oldPortID;
                    notifiedUUID[1] = newPortID;
                }
            });
        }

    }

    class TestRouterBuilder implements RouterBuilder, BuildCallCounter {
        int buildCallsCount = 0;
        ArpCache arpCache;
        UUID loadBalancerId;

        public void addNewArpEntry(IPv4Addr ipAddr, ArpCacheEntry entry) {
            arpCache.add(ipAddr, entry);
        }

        public ArpCacheEntry getArpEntryForIp(IPv4Addr ipAddr) {
            return arpCache.get(ipAddr);
        }

        public int getBuildCallsCount() {
            return buildCallsCount;
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
        public void setLoadBalancer(UUID loadBalancerID) {
            loadBalancerId = loadBalancerId;
        }

        @Override
        public void build() {
            buildCallsCount++;
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

    interface BuildCallCounter {
        int getBuildCallsCount();
    }

    public static void pollCallCounts(BuildCallCounter bldr, int nCalls)
            throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100);
            if (bldr.getBuildCallsCount() == nCalls)
                break;
        }
    }
}
