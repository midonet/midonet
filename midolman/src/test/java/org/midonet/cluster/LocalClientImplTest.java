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

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import scala.Option;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;

import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.client.BridgeBuilder;
import org.midonet.cluster.client.IpMacMap;
import org.midonet.cluster.client.MacLearningTable;
import org.midonet.cluster.client.RouterBuilder;
import org.midonet.cluster.client.VlanPortMap;
import org.midonet.cluster.storage.MidonetBackendTestModule;
import org.midonet.conf.MidoTestConfigurator;
import org.midonet.midolman.Setup;
import org.midonet.midolman.cluster.LegacyClusterModule;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.cluster.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.guice.config.MidolmanConfigModule;
import org.midonet.midolman.layer3.Route;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.ArpCache;
import org.midonet.midolman.state.ArpCacheEntry;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager.ChainConfig;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
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


    Config fillConfig() {
        return ConfigFactory.empty().withValue("zookeeper.root_key",
                         ConfigValueFactory.fromAnyRef(zkRoot));
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
        Config conf = MidoTestConfigurator.forAgents(fillConfig());
        injector = Guice.createInjector(
            new SerializationModule(),
            new MidolmanConfigModule(conf),
            new MockZookeeperConnectionModule(),
            new MidonetBackendTestModule(),
            new LegacyClusterModule()
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
        public void setLoadBalancer(UUID lbID) {
            loadBalancerId = lbID;
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
