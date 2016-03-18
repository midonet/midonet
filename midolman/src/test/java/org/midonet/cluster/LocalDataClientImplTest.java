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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.CreateMode;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.Route;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.RouterPort;
import org.midonet.midolman.layer3.Route.NextHop;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkLeaderElectionWatcher.ExecuteOnBecomingLeader;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;

/**
 * Tests for LocalDataClientImpl
 *
 * The tests are divided up into two types; those that rely on watchers and
 * those that don't.  For those that depend on watchers where they are
 * sensitive to timing, define them in LocalDataClientImplTestWithMockDirectory
 * where MockDirectory is used to emulate Zookeeper.  If the tests are not
 * sensitive to timing, define them in LocalDataClientImplTestWithZkServer
 * where they run against an actual Zookeeper server (in-memory).
 */
public class LocalDataClientImplTest {

    protected static Bridge getStockBridge() {
        return new Bridge()
            .setAdminStateUp(true);
    }

    protected static Router getStockRouter() {
        return new Router()
            .setAdminStateUp(true);
    }

    protected static Subnet getStockSubnet(String cidr) {
        return new Subnet().setSubnetAddr(IPv4Subnet.fromCidr(cidr));
    }

    protected static Port getStockBridgePort(UUID bridgeId) {
        return new BridgePort()
            .setDeviceId(bridgeId)
            .setAdminStateUp(true);
    }

    protected static Port getStockRouterPort(UUID routerId) {
        return new RouterPort()
            .setDeviceId(routerId)
            .setAdminStateUp(true);
    }

    protected static void assertSubnetCidrs(List<Subnet> actual,
                                         List<String> expectedCidrs) {
        assertThat(actual, notNullValue());
        assertThat(expectedCidrs, notNullValue());

        assertThat(actual.size(), equalTo(expectedCidrs.size()));

        for (Subnet actualSubnet : actual) {
            assertThat(expectedCidrs,
                    hasItem(actualSubnet.getSubnetAddr().toZkString()));
        }
    }

    public static class LocalDataClientImplTestWithZkServer
        extends ZookeeperTest {

        protected static TestingServer server;
        private DataClient client;
        private Directory directory;
        private PathBuilder pathBuilder;

        @BeforeClass
        public static void initZkTestingServer() throws Exception {
            if (server == null) {
                server = new TestingServer(ZK_PORT);
                server.start();
            }
        }

        @AfterClass
        public static void shutdownZkTestingServer() throws Exception {
            if (server != null) {
                try {
                    server.close();
                } catch (Throwable e) {
                    LoggerFactory.getLogger(LocalDataClientImplTest.class)
                        .warn("Failed to stop ZK testing server", e);
                } finally {
                    server = null;
                }
            }
        }

        @Before
        public void setUp() throws Exception {
            super.setUp();
            this.client = injector.getInstance(DataClient.class);
            this.directory = getDirectory();
            this.pathBuilder = getPathBuilder();
        }

        @Test
        public void routerPortLifecycleTest() throws StateAccessException,
                                                     SerializationException {
            // Create a materialized router port.
            UUID routerId = client.routersCreate(new Router());
            UUID portId = client.portsCreate(
                new RouterPort().setDeviceId(routerId)
                    .setHwAddr(MAC.fromString("02:BB:EE:EE:FF:01"))
                    .setPortAddr("10.0.0.3").setNwAddr("10.0.0.0")
                    .setNwLength(24)
            );
            // Verify that this automatically creates one route.
            List<Route> routes = client.routesFindByRouter(routerId);
            assertThat(routes, hasSize(1));
            Route rt = routes.get(0);
            // Verify that the route is type LOCAL and forwards to the new port.
            assertThat(rt.getNextHop(), equalTo(NextHop.LOCAL));
            assertThat(rt.getNextHopPort(), equalTo(portId));
            assertThat(rt.getNextHopGateway(), equalTo(
                IPv4Addr.intToString(
                    org.midonet.midolman.layer3.Route.NO_GATEWAY)));
            // Now delete the port and verify that the route is deleted.
            client.portsDelete(portId);
            routes = client.routesFindByRouter(routerId);
            assertThat(routes, hasSize(0));
        }

        @Test
        public void dhcpSubnetEnabledTest()
            throws StateAccessException, SerializationException {

            UUID bridgeId = client.bridgesCreate(getStockBridge());

            // Create an enabled subnet
            Subnet enabledSubnet = getStockSubnet("10.0.0.0/24");
            enabledSubnet.setEnabled(true);
            client.dhcpSubnetsCreate(bridgeId, enabledSubnet);

            // Create an enabled subnet, but not enabled explicitly
            Subnet defaultEnabledSubnet = getStockSubnet("10.0.1.0/24");
            client.dhcpSubnetsCreate(bridgeId, defaultEnabledSubnet);

            // Create a disabled subnet
            Subnet disabledSunbet = getStockSubnet("10.0.2.0/24");
            disabledSunbet.setEnabled(false);
            client.dhcpSubnetsCreate(bridgeId, disabledSunbet);

            // Get all to make ensure both return
            List<Subnet> subnets = client.dhcpSubnetsGetByBridge(bridgeId);
            assertSubnetCidrs(subnets,
                              Arrays.asList(
                                  "10.0.0.0_24",
                                  "10.0.1.0_24",
                                  "10.0.2.0_24"));

            // Get only the enabled and ensure only one return
            subnets = client.dhcpSubnetsGetByBridgeEnabled(bridgeId);
            assertSubnetCidrs(subnets,
                              Arrays.asList(
                                  "10.0.0.0_24",
                                  "10.0.1.0_24"));
        }

        @Test
        public void tryOwnVtepTest() throws Exception {
            UUID node1 = UUID.randomUUID();
            UUID node2 = UUID.randomUUID();

            IPv4Addr vtepIp = IPv4Addr.fromString("10.2.3.2");

            VTEP vtep = new VTEP();
            vtep.setMgmtPort(6333);
            vtep.setId(vtepIp);
            vtep.setTunnelZone(UUID.randomUUID());
            client.vtepCreate(vtep);

            UUID owner1 = client.tryOwnVtep(vtepIp, node1);
            UUID owner2 = client.tryOwnVtep(vtepIp, node2);
            UUID owner3 = client.tryOwnVtep(vtepIp, node1);

            assertEquals(node1, owner1);
            assertEquals(node1, owner2);
            assertEquals(node1, owner3);
        }

        @Test
        public void bridgePortDeletionWithLeftOverDataTest() throws Exception {
            UUID bridgeId = client.bridgesCreate(getStockBridge());
            UUID portId = client.portsCreate(getStockBridgePort(bridgeId));

            // Add data that used to exist in an older version
            directory.add(pathBuilder.getFilterSnatBlocksPath(portId),
                          null, CreateMode.PERSISTENT);

            assertThat(client.portsExists(portId), equalTo(true));

            client.portsDelete(portId);

            assertThat(client.portsExists(portId), equalTo(false));
        }

        @Test
        public void routerPortDeletionWithLeftOverDataTest() throws Exception {
            UUID routerId = client.routersCreate(getStockRouter());
            UUID portId = client.portsCreate(getStockRouterPort(routerId));

            // Add data that used to exist in an older version
            directory.add(pathBuilder.getFilterSnatBlocksPath(portId),
                          null, CreateMode.PERSISTENT);

            assertThat(client.portsExists(portId), equalTo(true));

            client.portsDelete(portId);

            assertThat(client.portsExists(portId), equalTo(false));
        }

        @Test
        public void bridgeDeletionWithLeftOverDataTest() throws Exception {
            UUID bridgeId = client.bridgesCreate(getStockBridge());
            UUID portId = client.portsCreate(getStockBridgePort(bridgeId));

            // Add data that used to exist in an older version
            directory.add(pathBuilder.getFilterSnatBlocksPath(portId),
                          null, CreateMode.PERSISTENT);
            directory.add(pathBuilder.getFilterSnatBlocksPath(bridgeId),
                          null, CreateMode.PERSISTENT);

            assertThat(client.portsExists(portId), equalTo(true));
            assertThat(client.bridgeExists(bridgeId), equalTo(true));

            client.bridgesDelete(bridgeId);

            assertThat(client.portsExists(portId), equalTo(false));
            assertThat(client.bridgeExists(bridgeId), equalTo(false));
        }

        @Test
        public void routerDeletionWithLeftOverDataTest() throws Exception {
            UUID routerId = client.routersCreate(getStockRouter());
            UUID portId = client.portsCreate(getStockRouterPort(routerId));

            // Add data that used to exist in an older version
            directory.add(pathBuilder.getFilterSnatBlocksPath(portId),
                          null, CreateMode.PERSISTENT);
            directory.add(pathBuilder.getFilterSnatBlocksPath(routerId),
                          null, CreateMode.PERSISTENT);

            assertThat(client.portsExists(portId), equalTo(true));
            assertThat(client.routerExists(routerId), equalTo(true));

            client.routersDelete(routerId);

            assertThat(client.portsExists(portId), equalTo(false));
            assertThat(client.routerExists(routerId), equalTo(false));
        }
    }

    public static class LocalDataClientImplTestWithMockDirectory
            extends LocalDataClientImplTestBase {

        private void assertIsLeader(boolean[] leaderArr, int leader) {
            assertThat(leaderArr[leader], equalTo(true));
            for (int i = 0; i < leaderArr.length; i++) {
                if (i != leader) {
                    assertThat(leaderArr[i], equalTo(false));
                }
            }
        }

        @Test
        public void checkHealthMonitorNodeTest() throws StateAccessException {
            final boolean[] currentLeader = {false, false, false, false};

            // The var accessed inside of functors has to be final, otherwise
            // I would have just used a single int.
            ExecuteOnBecomingLeader cb0 = new ExecuteOnBecomingLeader() {
                @Override
                public void call() {
                    currentLeader[0] = true;
                    currentLeader[1] = false;
                    currentLeader[2] = false;
                    currentLeader[3] = false;
                }
            };
            ExecuteOnBecomingLeader cb1 = new ExecuteOnBecomingLeader() {
                @Override
                public void call() {
                    currentLeader[0] = false;
                    currentLeader[1] = true;
                    currentLeader[2] = false;
                    currentLeader[3] = false;
                }
            };
            ExecuteOnBecomingLeader cb2 = new ExecuteOnBecomingLeader() {
                @Override
                public void call() {
                    /* Don't do anything. This makes the UT weaker, but we have
                     * no way to remove watches so even if we remove the
                     * node corresponding to this callback, the watch callback
                     * will still be triggered. This isn't a problem in
                     * production because the node is removed when the mm agent
                     * goes away.
                     *
                     * Functionality to remove watches is in ZooKeeper 3.5.0+
                     */
                }
            };
            ExecuteOnBecomingLeader cb3 = new ExecuteOnBecomingLeader() {
                @Override
                public void call() {
                    currentLeader[0] = false;
                    currentLeader[1] = false;
                    currentLeader[2] = false;
                    currentLeader[3] = true;
                }
            };

            Integer precLeader = client.getPrecedingHealthMonitorLeader(14);
            assertThat(precLeader, equalTo(null));

            Integer hostNum0 = client.registerAsHealthMonitorNode(cb0);
            // Make sure the preceding leader for an arbitrary number is
            // hostNum0
            precLeader = client.getPrecedingHealthMonitorLeader(1);
            assertThat(precLeader, equalTo(hostNum0));
            assertThat(hostNum0, equalTo(0));
            assertIsLeader(currentLeader, hostNum0);

            Integer hostNum1 = client.registerAsHealthMonitorNode(cb1);
            // Make sure the preceding leader for an arbitrary number is
            // hostNum1
            precLeader = client.getPrecedingHealthMonitorLeader(6);
            assertThat(precLeader, equalTo(hostNum1));
            assertThat(hostNum1, equalTo(1));
            assertIsLeader(currentLeader, hostNum0);

            // host 0 goes down...
            client.removeHealthMonitorLeaderNode(hostNum0);
            assertIsLeader(currentLeader, hostNum1);

            Integer hostNum2 = client.registerAsHealthMonitorNode(cb2);
            precLeader = client.getPrecedingHealthMonitorLeader(hostNum2);
            assertThat(precLeader, equalTo(hostNum1));
            assertThat(hostNum2, equalTo(2));
            assertIsLeader(currentLeader, hostNum1);

            Integer hostNum3 = client.registerAsHealthMonitorNode(cb3);
            precLeader = client.getPrecedingHealthMonitorLeader(hostNum3);
            assertThat(precLeader, equalTo(hostNum2));

            // host 2 goes down
            client.removeHealthMonitorLeaderNode(hostNum2);
            assertIsLeader(currentLeader, hostNum1);
            precLeader = client.getPrecedingHealthMonitorLeader(hostNum3);
            assertThat(precLeader, equalTo(hostNum1));

            //host 1 goes down
            client.removeHealthMonitorLeaderNode(hostNum1);
            assertIsLeader(currentLeader, hostNum3);
            precLeader = client.getPrecedingHealthMonitorLeader(hostNum3);
            assertThat(precLeader, equalTo(null));
        }
    }
}
