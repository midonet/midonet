package com.midokura.midolman.layer3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.layer3.Router.Action;
import com.midokura.midolman.layer3.Router.ForwardInfo;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.MockCache;

public class TestNetwork {

    private Network network;
    private List<L3DevicePort> devPorts;
    private List<UUID> routerIds;
    private MockReactor reactor;
    private MockControllerStub controllerStub;

    @Before
    public void setUp() throws Exception {
        devPorts = new ArrayList<L3DevicePort>();
        routerIds = new ArrayList<UUID>();
        reactor = new MockReactor();
        controllerStub = new MockControllerStub();

        String basePath = "/midolman";
        ZkPathManager pathMgr = new ZkPathManager(basePath);
        Directory dir = new MockDirectory();
        dir.add(pathMgr.getBasePath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getChainsPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRulesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutersPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getPortsPath(), null, CreateMode.PERSISTENT);
        PortZkManager portMgr = new PortZkManager(dir, basePath);
        RouteZkManager routeMgr = new RouteZkManager(dir, basePath);
        RouterZkManager routerMgr = new RouterZkManager(dir, basePath);

        network = new Network(new UUID(19, 19), portMgr, routerMgr,
                new ChainZkManager(dir, basePath), new RuleZkManager(dir,
                        basePath), reactor, new MockCache());

        /*
         * Create 3 routers such that: 1) router0 handles traffic to 10.0.0.0/16
         * 2) router1 handles traffic to 10.1.0.0/16 3) router2 handles traffic
         * to 10.2.0.0/16 4) router0 and router1 are connected via logical
         * ports. 5) router0 and router2 are connected via logical ports 6)
         * router0 is the default next hop for router1 and router2 7) router0
         * has a single uplink to the global internet.
         */
        Route rt;
        PortDirectory.MaterializedRouterPortConfig portConfig;
        for (int i = 0; i < 3; i++) {
            UUID rtrId = routerMgr.create();
            routerIds.add(rtrId);
            // This router handles all traffic to 10.<i>.0.0/16
            int routerNw = 0x0a000000 + (i << 16);
            // With low weight, reject anything that is in this router's NW.
            // Routes associated with ports can override this.
            rt = new Route(0, 0, routerNw, 16, NextHop.REJECT, null, 0, 100,
                    null, rtrId);
            routeMgr.create(rt);
            // Manually add this route to the replicated routing table since
            // it's not associated with any port.
            network.getRouter(rtrId).table.addRoute(rt);

            // Add two ports to the router. Port-j should route to subnet
            // 10.<i>.<j>.0/24.
            for (int j = 0; j < 2; j++) {
                int portNw = routerNw + (j << 8);
                int portAddr = portNw + 1;
                short portNum = (short) (i * 10 + j);
                portConfig = new PortDirectory.MaterializedRouterPortConfig(
                        rtrId, portNw, 24, portAddr, null, portNw, 24, null);
                UUID portId = portMgr.create(portConfig);
                rt = new Route(0, 0, portNw, 24, NextHop.PORT, portId,
                        Route.NO_GATEWAY, 2, null, rtrId);
                routeMgr.create(rt);
                // All the ports will be local to this controller.
                L3DevicePort devPort = new L3DevicePort(portMgr, routeMgr,
                        portId, portNum, new MAC(new byte[] { (byte) 0x02, (byte) 0x00,
                                (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                (byte) portNum }), controllerStub);
                network.addPort(devPort);
                devPorts.add(devPort);
            }
        }
        // Now add the logical links between router 0 and 1.
        // First from 0 to 1
        PortDirectory.LogicalRouterPortConfig logPortConfig1 = new PortDirectory.LogicalRouterPortConfig(
                routerIds.get(0), 0xc0a80100, 30, 0xc0a80101, null, null);
        PortDirectory.LogicalRouterPortConfig logPortConfig2 = new PortDirectory.LogicalRouterPortConfig(
                routerIds.get(1), 0xc0a80100, 30, 0xc0a80102, null, null);
        ZkNodeEntry<UUID, UUID> idPair = portMgr.createLink(logPortConfig1,
                logPortConfig2);
        UUID portOn0to1 = idPair.key;
        UUID portOn1to0 = idPair.value;
        rt = new Route(0, 0, 0x0a010000, 16, NextHop.PORT, portOn0to1,
                0xc0a80102, 2, null, routerIds.get(0));
        routeMgr.create(rt);
        network.getRouter(routerIds.get(0)).table.addRoute(rt);
        // Now from 1 to 0. Note that this is router1's uplink.
        rt = new Route(0, 0, 0, 0, NextHop.PORT, portOn1to0, 0xc0a80101,
                10, null, routerIds.get(1));
        routeMgr.create(rt);
        network.getRouter(routerIds.get(1)).table.addRoute(rt);
        // Now add the logical links between router 0 and 2.
        // First from 0 to 2
        logPortConfig1 = new PortDirectory.LogicalRouterPortConfig(routerIds
                .get(0), 0xc0a80100, 30, 0xc0a80101, null, null);
        logPortConfig2 = new PortDirectory.LogicalRouterPortConfig(routerIds
                .get(2), 0xc0a80100, 30, 0xc0a80102, null, null);
        idPair = portMgr.createLink(logPortConfig1, logPortConfig2);
        UUID portOn0to2 = idPair.key;
        UUID portOn2to0 = idPair.value;
        rt = new Route(0, 0, 0x0a020000, 16, NextHop.PORT, portOn0to2,
                0xc0a80102, 2, null, routerIds.get(0));
        routeMgr.create(rt);
        network.getRouter(routerIds.get(0)).table.addRoute(rt);
        rt = new Route(0, 0, 0, 0, NextHop.PORT, portOn2to0, 0xc0a80101,
                10, null, routerIds.get(2));
        routeMgr.create(rt);
        network.getRouter(routerIds.get(2)).table.addRoute(rt);

        // Finally, instead of giving router0 an uplink. Add a route that
        // drops anything that isn't going to router0's local or logical ports.
        rt = new Route(0, 0, 0x0a000000, 8, NextHop.BLACKHOLE, null, 0, 2,
                null, routerIds.get(0));
        routeMgr.create(rt);
        network.getRouter(routerIds.get(0)).table.addRoute(rt);
    }

    public static ForwardInfo prepareFwdInfo(UUID inPortId, Ethernet ethPkt) {
        byte[] pktData = ethPkt.serialize();
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(pktData, (short) 0);
        ForwardInfo fInfo = new ForwardInfo();
        fInfo.inPortId = inPortId;
        fInfo.matchIn = match;
        fInfo.pktIn = ethPkt;
        return fInfo;
    }

    @Test
    public void testOneRouterBlackhole() throws StateAccessException,
            ZkStateSerializationException, IOException {
        // Send a packet to router0's first materialized port to a destination
        // that's blackholed.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        L3DevicePort ingrDevPort = devPorts.get(0);
        Ethernet eth = TestRouter.makeUDP(
                MAC.fromString("02:00:11:22:00:01"), ingrDevPort.getMacAddr(),
                0x0a000005, 0x0a040005, (short) 101, (short) 212, payload);
        ForwardInfo fInfo = prepareFwdInfo(ingrDevPort.getId(), eth);
        Set<UUID> traversedRtrs = new HashSet<UUID>();
        network.process(fInfo, traversedRtrs);
        Assert.assertEquals(1, traversedRtrs.size());
        Assert.assertTrue(traversedRtrs.contains(routerIds.get(0)));
        TestRouter.checkForwardInfo(fInfo, Action.BLACKHOLE, null, 0);
    }

    @Test
    public void testOneRouterReject() throws ZkStateSerializationException,
            StateAccessException, IOException {
        // Send a packet to router0's first materialized port to a destination
        // that's rejected.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        L3DevicePort ingrDevPort = devPorts.get(0);
        Ethernet eth = TestRouter.makeUDP(
                MAC.fromString("02:00:11:22:00:01"), ingrDevPort.getMacAddr(),
                0x0a000005, 0x0a000c05, (short) 101, (short) 212, payload);
        ForwardInfo fInfo = prepareFwdInfo(ingrDevPort.getId(), eth);
        Set<UUID> traversedRtrs = new HashSet<UUID>();
        network.process(fInfo, traversedRtrs);
        Assert.assertEquals(1, traversedRtrs.size());
        Assert.assertTrue(traversedRtrs.contains(routerIds.get(0)));
        TestRouter.checkForwardInfo(fInfo, Action.REJECT, null, 0);
    }

    @Test
    public void testOneRouterForward() throws StateAccessException,
            ZkStateSerializationException, IOException {
        // Send a packet to router0's first materialized port to a destination
        // reachable from its second materialized port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        L3DevicePort ingrDevPort = devPorts.get(0);
        L3DevicePort egrDevPort = devPorts.get(1);
        Ethernet eth = TestRouter.makeUDP(
                MAC.fromString("02:00:11:22:00:01"), ingrDevPort.getMacAddr(),
                0x0a000005, 0x0a000105, (short) 101, (short) 212, payload);
        ForwardInfo fInfo = prepareFwdInfo(ingrDevPort.getId(), eth);
        Set<UUID> traversedRtrs = new HashSet<UUID>();
        network.process(fInfo, traversedRtrs);
        Assert.assertEquals(1, traversedRtrs.size());
        Assert.assertTrue(traversedRtrs.contains(routerIds.get(0)));
        TestRouter.checkForwardInfo(fInfo, Action.FORWARD, egrDevPort.getId(),
                0x0a000105);
    }

    @Test
    public void testTwoRoutersForward() throws StateAccessException,
            ZkStateSerializationException, IOException {
        // Send a packet to router1's first materialized port to a destination
        // reachable from router0's first materialized port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        L3DevicePort ingrDevPort = devPorts.get(2);
        L3DevicePort egrDevPort = devPorts.get(0);
        Ethernet eth = TestRouter.makeUDP(
                MAC.fromString("02:00:11:22:00:01"), ingrDevPort.getMacAddr(),
                0x0a0100cc, 0x0a0000aa, (short) 101, (short) 212, payload);
        ForwardInfo fInfo = prepareFwdInfo(ingrDevPort.getId(), eth);
        Set<UUID> traversedRtrs = new HashSet<UUID>();
        network.process(fInfo, traversedRtrs);
        Assert.assertEquals(2, traversedRtrs.size());
        Assert.assertTrue(traversedRtrs.contains(routerIds.get(0)));
        Assert.assertTrue(traversedRtrs.contains(routerIds.get(1)));
        TestRouter.checkForwardInfo(fInfo, Action.FORWARD, egrDevPort.getId(),
                0x0a0000aa);
    }

    @Test
    public void testThreeRoutersForward() throws StateAccessException,
            ZkStateSerializationException, IOException {
        // Send a packet to router1's second materialized port to a destination
        // reachable from router2's second materialized port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        L3DevicePort ingrDevPort = devPorts.get(3);
        L3DevicePort egrDevPort = devPorts.get(5);
        Ethernet eth = TestRouter.makeUDP(
                MAC.fromString("02:00:11:22:00:01"), ingrDevPort.getMacAddr(),
                0x0a0101bb, 0x0a020188, (short) 101, (short) 212, payload);
        ForwardInfo fInfo = prepareFwdInfo(ingrDevPort.getId(), eth);
        Set<UUID> traversedRtrs = new HashSet<UUID>();
        network.process(fInfo, traversedRtrs);
        Assert.assertEquals(3, traversedRtrs.size());
        Assert.assertTrue(traversedRtrs.contains(routerIds.get(0)));
        Assert.assertTrue(traversedRtrs.contains(routerIds.get(1)));
        Assert.assertTrue(traversedRtrs.contains(routerIds.get(2)));
        TestRouter.checkForwardInfo(fInfo, Action.FORWARD, egrDevPort.getId(),
                0x0a020188);
    }

    @Test
    public void testArpRequestGeneration() throws ZkStateSerializationException {
        // Try to get the MAC for a nwAddr on router2's second port (i.e. in
        // 10.2.1.0/24).
        TestRouter.ArpCompletedCallback cb = new TestRouter.ArpCompletedCallback();
        L3DevicePort devPort = devPorts.get(5);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        network.getMacForIp(devPort.getId(), 0x0a020123, cb);
        // There should now be an ARP request in the MockProtocolStub
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(devPort.getNum(), (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Ethernet expectedArp = TestRouter.makeArpRequest(devPort.getMacAddr(),
                devPort.getVirtualConfig().portAddr, 0x0a020123);
        Assert.assertArrayEquals(expectedArp.serialize(), pkt.data);
    }

    @Ignore
    @Test
    public void testPortConfigChanges() {

    }

}
