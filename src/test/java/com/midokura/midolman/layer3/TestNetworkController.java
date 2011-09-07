package com.midokura.midolman.layer3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus;
import org.openflow.protocol.action.OFAction;

import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortLocationMap;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.RouterDirectory.RouterConfig;

public class TestNetworkController {

    private NetworkController networkCtrl;
    private List<OFPhysicalPort> phyPorts;
    private List<UUID> routerIds;
    private MockReactor reactor;
    private MockControllerStub controllerStub;

    @Before
    public void setUp() throws Exception {
        phyPorts = new ArrayList<OFPhysicalPort>();
        routerIds = new ArrayList<UUID>();
        reactor = new MockReactor();
        controllerStub = new MockControllerStub();

        List<UUID> portIds = new ArrayList<UUID>();

        Directory dir = new MockDirectory();
        dir.add("/midonet", null, CreateMode.PERSISTENT);
        dir.add("/midonet/ports", null, CreateMode.PERSISTENT);
        Directory portsSubdir = dir.getSubDirectory("/midonet/ports");
        PortDirectory portDir = new PortDirectory(portsSubdir);

        dir.add("/midonet/routers", null, CreateMode.PERSISTENT);
        Directory routersSubdir = dir.getSubDirectory("/midonet/routers");
        RouterDirectory routerDir = new RouterDirectory(routersSubdir);

        // Now build the Port to Location Map.
        UUID networkId = new UUID(1, 1);
        StringBuilder strBuilder = new StringBuilder("/midonet/networks");
        dir.add(strBuilder.toString(), null, CreateMode.PERSISTENT);
        strBuilder.append('/').append(networkId.toString());
        dir.add(strBuilder.toString(), null, CreateMode.PERSISTENT);
        strBuilder.append("/portLocation");
        dir.add(strBuilder.toString(), null, CreateMode.PERSISTENT);
        Directory portLocSubdir = dir.getSubDirectory(strBuilder.toString());
        PortLocationMap portLocMap = new PortLocationMap(portLocSubdir);

        // Now create the Open vSwitch database connection
        MockOpenvSwitchDatabaseConnection ovsdb = 
                new MockOpenvSwitchDatabaseConnection();

        // Now we can create the NetworkController itself.
        int localNwAddr = 0xc0a80104;
        int datapathId = 43;
        networkCtrl = new NetworkController(datapathId, networkId,
                5 /* greKey */, portLocMap, 60*1000, localNwAddr, routerDir,
                portDir, ovsdb, reactor);
        networkCtrl.setControllerStub(controllerStub);

        /*
         * Create 3 routers such that: 1) router0 handles traffic to 10.0.0.0/16
         * 2) router1 handles traffic to 10.1.0.0/16 3) router2 handles traffic
         * to 10.2.0.0/16 4) router0 and router1 are connected via logical
         * ports. 5) router0 and router2 are connected via logical ports 6)
         * router0 is the default next hop for router1 and router2 7) router0
         * has a single uplink to the global internet.
         */
        Set<Route> routes = new HashSet<Route>();
        Route rt;
        MaterializedRouterPortConfig portConfig;
        List<ReplicatedRoutingTable> rTables = new ArrayList<ReplicatedRoutingTable>();
        for (int i = 0; i < 3; i++) {
            UUID rtrId = new UUID(1234, i);
            UUID tenantId = new UUID(5678, i);
            routerIds.add(rtrId);
            RouterConfig routerConfig = new RouterConfig("Test Router " + i, 
                    tenantId);
            routerDir.addRouter(rtrId, routerConfig);
            Directory tableDir = routerDir.getRoutingTableDirectory(rtrId);
            ReplicatedRoutingTable rTable = new ReplicatedRoutingTable(
                    rtrId, tableDir, CreateMode.PERSISTENT);
            rTables.add(rTable);

            // This router handles all traffic to 10.<i>.0.0/16
            int routerNw = 0x0a000000 + (i << 16);
            // With low weight, reject anything that is in this router's NW.
            // Routes associated with ports can override this.
            rt = new Route(0, 0, routerNw, 16, NextHop.REJECT, null, 0, 100,
                    null);
            routerDir.addRoute(rtrId, rt);
            // Manually add this route to the replicated routing table since
            // it's not associated with any port.
            rTable.addRoute(rt);

            // Add two ports to the router. Port-j should route to subnet
            // 10.<i>.<j>.0/24.
            for (int j = 0; j < 2; j++) {
                int portNw = routerNw + (j << 8);
                int portAddr = portNw + 1;
                short portNum = (short) (i * 10 + j);
                UUID portId = PortDirectory.intTo32BitUUID(portNum);
                routes.clear();
                rt = new Route(0, 0, portNw, 24, NextHop.PORT, portId, 0, 2,
                        null);
                routes.add(rt);
                portConfig = new MaterializedRouterPortConfig(rtrId, portNw,
                        24, portAddr, routes, portNw, 24, null);
                portDir.addPort(portId, portConfig);

                // Add odd-numbered materialized ports to the local controller.
                if (0 == j % 2) {
                    ovsdb.setPortExternalId(datapathId, portNum, "midonet",
                            portId.toString());
                    OFPhysicalPort phyPort = new OFPhysicalPort();
                    phyPort.setPortNumber(portNum);
                    phyPort.setHardwareAddress(new byte[] {(byte)0x02, (byte)0xee, 
                            (byte)0xdd, (byte)0xcc, (byte)0xff, (byte)portNum});
                    networkCtrl.onPortStatus(phyPort,
                            OFPortStatus.OFPortReason.OFPPR_ADD);
                    phyPorts.add(phyPort);
                }
                // TODO(pino): the other ports should be on different hosts.
                // Add them to the location map and open tunnels.
            }
        }
        // Now add the logical links between router 0 and 1.
        UUID portOn0to1 = PortDirectory.intTo32BitUUID(331);
        UUID portOn1to0 = PortDirectory.intTo32BitUUID(332);
        // First from 0 to 1
        rt = new Route(0, 0, 0x0a010000, 16, NextHop.PORT, portOn0to1, 0, 2,
                null);
        routes.clear();
        routes.add(rt);
        LogicalRouterPortConfig logPortConfig = new LogicalRouterPortConfig(
                routerIds.get(0), 0xc0a80100, 30, 0xc0a80101, routes, portOn1to0);
        portDir.addPort(portOn0to1, logPortConfig);
        // Manually add this route since it no local controller owns it.
        rTables.get(0).addRoute(rt);
        // Now from 1 to 0. Note that this is router1's uplink.
        rt = new Route(0, 0, 0, 0, NextHop.PORT, portOn1to0, 0, 10, null);
        routes.clear();
        routes.add(rt);
        logPortConfig = new LogicalRouterPortConfig(routerIds.get(1), 0xc0a80100,
                30, 0xc0a80102, routes, portOn0to1);
        portDir.addPort(portOn1to0, logPortConfig);
        // Manually add this route since it no local controller owns it.
        rTables.get(1).addRoute(rt);
        // Now add the logical links between router 0 and 2.
        UUID portOn0to2 = PortDirectory.intTo32BitUUID(333);
        UUID portOn2to0 = PortDirectory.intTo32BitUUID(334);
        // First from 0 to 2
        rt = new Route(0, 0, 0x0a020000, 16, NextHop.PORT, portOn0to2, 0, 2,
                null);
        routes.clear();
        routes.add(rt);
        logPortConfig = new LogicalRouterPortConfig(routerIds.get(0), 0xc0a80100,
                30, 0xc0a80101, routes, portOn2to0);
        portDir.addPort(portOn0to2, logPortConfig);
        // Manually add this route since it no local controller owns it.
        rTables.get(0).addRoute(rt);
        // Now from 2 to 0. Note that this is router2's uplink.
        rt = new Route(0, 0, 0, 0, NextHop.PORT, portOn2to0, 0, 10, null);
        routes.clear();
        routes.add(rt);
        logPortConfig = new LogicalRouterPortConfig(routerIds.get(2), 0xc0a80100,
                30, 0xc0a80102, routes, portOn0to2);
        portDir.addPort(portOn2to0, logPortConfig);
        // Manually add this route since it no local controller owns it.
        rTables.get(2).addRoute(rt);

        // Finally, instead of giving router0 an uplink. Add a route that
        // drops anything that isn't going to router0's local or logical ports.
        rt = new Route(0, 0, 0x0a000000, 8, NextHop.BLACKHOLE, null, 0, 2, null);
        routerDir.addRoute(routerIds.get(0), rt);
        // Manually add this route since it no local controller owns it.
        rTables.get(0).addRoute(rt);
    }

    public static void checkInstalledFlow(MockControllerStub.Flow flow,
            OFMatch match, short idleTimeoutSecs,
            int bufferId, boolean sendFlowRemove, List<OFAction> actions) {
        Assert.assertTrue(match.equals(flow.match));
        Assert.assertEquals(idleTimeoutSecs, flow.idleTimeoutSecs);
        Assert.assertEquals(bufferId, flow.bufferId);
        Assert.assertEquals(sendFlowRemove, flow.sendFlowRemove);
        Assert.assertEquals(actions.size(), flow.actions.size());
        for (int i = 0; i < actions.size(); i++)
            Assert.assertTrue(actions.get(i).equals(flow.actions.get(i)));
    }

    @Test
    public void testOneRouterBlackhole() {
        // Send a packet to router0's first materialized port to a destination
        // that's blackholed.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPort = phyPorts.get(0);
        Ethernet eth = TestRouter.makeUDP(Ethernet
                .toMACAddress("02:00:11:22:00:01"), phyPort.getHardwareAddress(),
                0x0a000005, 0x0a040005, (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(55, data.length, phyPort.getPortNumber(), data);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 55, true, actions);
    }

}
