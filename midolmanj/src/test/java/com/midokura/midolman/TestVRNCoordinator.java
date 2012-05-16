/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.ForwardingElement.Action;
import com.midokura.midolman.ForwardingElement.ForwardInfo;
import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.layer3.L3DevicePort;
import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.layer3.TestRouter;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.rules.ChainProcessor;
import com.midokura.midolman.state.*;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.MockCache;

public class TestVRNCoordinator {

    private static final Logger log =
        LoggerFactory.getLogger(TestVRNCoordinator.class);

    private VRNCoordinator vrn;
    private List<L3DevicePort> devPorts;
    private List<UUID> routerIds;
    private MockReactor reactor;
    private MockControllerStub controllerStub;
    private MockVRNController controller;
    private PortZkManager portMgr;

    protected Cache createCache() {
        return new MockCache();
    }

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
        dir.add(pathMgr.getFiltersPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRulesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutersPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getPortsPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getGrePath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getVRNPortLocationsPath(), null, CreateMode.PERSISTENT);
        portMgr = new PortZkManager(dir, basePath);
        RouteZkManager routeMgr = new RouteZkManager(dir, basePath);
        RouterZkManager routerMgr = new RouterZkManager(dir, basePath);

        controller = new MockVRNController(679, dir, basePath, null,
                   IntIPv4.fromString("192.168.200.200"), "externalIdKey");
        PortSetMap portSetMap = new PortSetMap(dir, basePath);
        Cache cache = createCache();
        ChainProcessor chainProcessor = new ChainProcessor(dir, basePath,
                cache, reactor);
        vrn = new VRNCoordinator(dir, basePath, reactor, cache,
                controller, portSetMap, chainProcessor);

        /*
         * Create 3 routers such that:
         *   1) router0 handles traffic to 10.0.0.0/16
         *   2) router1 handles traffic to 10.1.0.0/16
         *   3) router2 handles traffic to 10.2.0.0/16
         *   4) router0 and router1 are connected via logical ports.
         *   5) router0 and router2 are connected via logical ports.
         *   6) router0 is the default next hop for router1 and router2
         *   7) router0 has a single uplink to the global internet.
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

            // Add two ports to the router. Port-j should route to subnet
            // 10.<i>.<j>.0/24.
            for (int j = 0; j < 2; j++) {
                int portNw = routerNw + (j << 8);
                int portAddr = portNw + 1;
                short portNum = (short) (i * 10 + j);
                MAC mac = new MAC(new byte[] { (byte) 0x02,
                        (byte) 0x00, (byte) 0x00, (byte) 0x00,
                        (byte) 0x00, (byte) portNum });
                portConfig = new PortDirectory.MaterializedRouterPortConfig(
                        rtrId, portNw, 24, portAddr, mac, null, portNw, 24,
                        null);
                UUID portId = portMgr.create(portConfig);
                rt = new Route(0, 0, portNw, 24, NextHop.PORT, portId,
                        Route.NO_GATEWAY, 2, null, rtrId);
                routeMgr.create(rt);
                // All the ports will be local to this controller.
                // TODO: Should we construct this here, given that the Router
                // will construct a clone?
                L3DevicePort devPort = new L3DevicePort(portMgr, routeMgr,
                        portId);
                devPorts.add(devPort);
                vrn.addPort(portId);
            }
        }
        // Now add the logical links between router 0 and 1.
        // First from 0 to 1
        PortDirectory.LogicalRouterPortConfig logPortConfig1 =
                new PortDirectory.LogicalRouterPortConfig(
                        routerIds.get(0), 0xc0a80100, 30, 0xc0a80101,
                        null, null, null);
        PortDirectory.LogicalRouterPortConfig logPortConfig2 =
                new PortDirectory.LogicalRouterPortConfig(
                        routerIds.get(1), 0xc0a80100, 30, 0xc0a80102,
                        null, null, null);
        ZkNodeEntry<UUID, UUID> idPair = portMgr.createLink(logPortConfig1,
                logPortConfig2);
        UUID portOn0to1 = idPair.key;
        UUID portOn1to0 = idPair.value;
        rt = new Route(0, 0, 0x0a010000, 16, NextHop.PORT, portOn0to1,
                0xc0a80102, 2, null, routerIds.get(0));
        routeMgr.create(rt);
        // Now from 1 to 0. Note that this is router1's uplink.
        rt = new Route(0, 0, 0, 0, NextHop.PORT, portOn1to0, 0xc0a80101,
                10, null, routerIds.get(1));
        routeMgr.create(rt);
        // Now add the logical links between router 0 and 2.
        // First from 0 to 2
        logPortConfig1 = new PortDirectory.LogicalRouterPortConfig(
                routerIds.get(0), 0xc0a80100, 30, 0xc0a80101, null, null, null);
        logPortConfig2 = new PortDirectory.LogicalRouterPortConfig(
                routerIds.get(2), 0xc0a80100, 30, 0xc0a80102, null, null, null);
        idPair = portMgr.createLink(logPortConfig1, logPortConfig2);
        UUID portOn0to2 = idPair.key;
        UUID portOn2to0 = idPair.value;
        rt = new Route(0, 0, 0x0a020000, 16, NextHop.PORT, portOn0to2,
                0xc0a80102, 2, null, routerIds.get(0));
        routeMgr.create(rt);
        rt = new Route(0, 0, 0, 0, NextHop.PORT, portOn2to0, 0xc0a80101,
                10, null, routerIds.get(2));
        routeMgr.create(rt);

        // Finally, instead of giving router0 an uplink, add a route that
        // drops anything that isn't going to router0's local or logical ports.
        rt = new Route(0, 0, 0x0a000000, 8, NextHop.BLACKHOLE, null, 0, 2,
                null, routerIds.get(0));
        routeMgr.create(rt);
    }

    public static ForwardInfo prepareFwdInfo(UUID inPortId, Ethernet ethPkt) {
        MidoMatch match = AbstractController.createMatchFromPacket(
                ethPkt, (short)0);
        ForwardInfo fInfo = new ForwardInfo();
        fInfo.inPortId = inPortId;
        fInfo.flowMatch = match;
        fInfo.matchIn = match;
        fInfo.pktIn = ethPkt;
        return fInfo;
    }

    @Test
    public void testFwdInfoMultiplityCount() {
        ForwardInfo fi = new ForwardInfo();
        UUID id = UUID.randomUUID();
        Assert.assertEquals(0, fi.getTimesTraversed(id));
        fi.addTraversedFE(id);
        Assert.assertEquals(1, fi.getTimesTraversed(id));
        fi.addTraversedFE(id);
        Assert.assertEquals(2, fi.getTimesTraversed(id));
        fi.addTraversedFE(id);
        Assert.assertEquals(3, fi.getTimesTraversed(id));
    }

    @Test
    public void testOneRouterBlackhole() throws StateAccessException,
            ZkStateSerializationException, IOException, JMException,
            KeeperException {
        // Send a packet to router0's first materialized port to a destination
        // that's blackholed.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        L3DevicePort ingrDevPort = devPorts.get(0);
        Ethernet eth = TestRouter.makeUDP(
                MAC.fromString("02:00:11:22:00:01"), ingrDevPort.getMacAddr(),
                0x0a000005, 0x0a040005, (short) 101, (short) 212, payload);
        ForwardInfo fInfo = prepareFwdInfo(ingrDevPort.getId(), eth);
        vrn.process(fInfo);
        Assert.assertEquals(1, fInfo.getNumFEsTraversed());
        Assert.assertEquals(1, fInfo.getTimesTraversed(routerIds.get(0)));
        Assert.assertEquals(0, fInfo.getTimesTraversed(routerIds.get(1)));
        Assert.assertEquals(0, fInfo.getTimesTraversed(routerIds.get(2)));
        // TODO(pino): changed BLACKHOLE to DROP, check ICMP wasn't sent.
        TestRouter.checkForwardInfo(fInfo, Action.DROP, null, 0);
    }

    @Test
    public void testOneRouterReject() throws ZkStateSerializationException,
            StateAccessException, IOException, JMException, KeeperException {
        // Send a packet to router0's first materialized port to a destination
        // that's rejected.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        L3DevicePort ingrDevPort = devPorts.get(0);
        Ethernet eth = TestRouter.makeUDP(
                MAC.fromString("02:00:11:22:00:01"), ingrDevPort.getMacAddr(),
                0x0a000005, 0x0a000c05, (short) 101, (short) 212, payload);
        ForwardInfo fInfo = prepareFwdInfo(ingrDevPort.getId(), eth);
        vrn.process(fInfo);
        Assert.assertEquals(1, fInfo.getNumFEsTraversed());
        Assert.assertEquals(1, fInfo.getTimesTraversed(routerIds.get(0)));
        Assert.assertEquals(0, fInfo.getTimesTraversed(routerIds.get(1)));
        Assert.assertEquals(0, fInfo.getTimesTraversed(routerIds.get(2)));
        // TODO(pino): changed REJECT to DROP, check ICMP was sent.
        TestRouter.checkForwardInfo(fInfo, Action.DROP, null, 0);
    }

    @Test
    public void testOneRouterForward() throws StateAccessException,
            ZkStateSerializationException, IOException, JMException,
            KeeperException {
        // Send a packet to router0's first materialized port to a destination
        // reachable from its second materialized port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        L3DevicePort ingrDevPort = devPorts.get(0);
        L3DevicePort egrDevPort = devPorts.get(1);
        Ethernet eth = TestRouter.makeUDP(
                MAC.fromString("02:00:11:22:00:01"), ingrDevPort.getMacAddr(),
                0x0a000005, 0x0a000105, (short) 101, (short) 212, payload);
        ForwardInfo fInfo = prepareFwdInfo(ingrDevPort.getId(), eth);
        vrn.process(fInfo);
        Assert.assertEquals(1, fInfo.getNumFEsTraversed());
        Assert.assertEquals(1, fInfo.getTimesTraversed(routerIds.get(0)));
        Assert.assertEquals(0, fInfo.getTimesTraversed(routerIds.get(1)));
        Assert.assertEquals(0, fInfo.getTimesTraversed(routerIds.get(2)));
        // Router ARPs, verify it PAUSED the packet.
        Assert.assertEquals(Action.PAUSED, fInfo.action);

        // Feed the router an ARP reply, verify it forwards the packet now.
        // Construct an ARP reply for 0x0a000105.
        MAC remoteMAC = new MAC(new byte[] { (byte) 10, (byte) 2, (byte) 1,
                                             (byte) 2, (byte) 3, (byte) 3 });
        Ethernet arpReply = TestRouter.makeArpReply(remoteMAC,
                                egrDevPort.getMacAddr(), 0x0a000105,
                                egrDevPort.getIPAddr());
        ForwardInfo arpFInfo = prepareFwdInfo(egrDevPort.getId(), arpReply);
        vrn.process(arpFInfo);
        // Original packet should now get forwarded.
        TestRouter.checkForwardInfo(fInfo, Action.FORWARD, egrDevPort.getId(),
                0x0a000105);
    }

    @Test
    public void testTwoRoutersForward() throws StateAccessException,
            ZkStateSerializationException, IOException, JMException,
            KeeperException {
        // Send a packet to router1's first materialized port to a destination
        // reachable from router0's first materialized port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        L3DevicePort ingrDevPort = devPorts.get(2);
        L3DevicePort egrDevPort = devPorts.get(0);
        Ethernet eth = TestRouter.makeUDP(
                MAC.fromString("02:00:11:22:00:01"), ingrDevPort.getMacAddr(),
                0x0a0100cc, 0x0a0000aa, (short) 101, (short) 212, payload);
        ForwardInfo fInfo1 = prepareFwdInfo(ingrDevPort.getId(), eth);
        vrn.process(fInfo1);
        // The packet gets paused for ARP.
        Assert.assertEquals(Action.PAUSED, fInfo1.action);
        Assert.assertEquals(2, fInfo1.getNumFEsTraversed());
        Assert.assertEquals(1, fInfo1.getTimesTraversed(routerIds.get(0)));
        Assert.assertEquals(1, fInfo1.getTimesTraversed(routerIds.get(1)));
        Assert.assertEquals(0, fInfo1.getTimesTraversed(routerIds.get(2)));

        // Construct an ARP reply for 0x0a0000aa.
        MAC remoteMAC = new MAC(new byte[] { (byte) 10, (byte) 2, (byte) 1,
                                             (byte) 2, (byte) 3, (byte) 3 });
        Ethernet arpReply = TestRouter.makeArpReply(remoteMAC,
                                egrDevPort.getMacAddr(), 0x0a0000aa,
                                egrDevPort.getIPAddr());
        ForwardInfo arpFInfo = prepareFwdInfo(egrDevPort.getId(), arpReply);
        vrn.process(arpFInfo);
        // ... and the ARP reply causes the first packet's journey to finish.
        TestRouter.checkForwardInfo(fInfo1, Action.FORWARD, egrDevPort.getId(),
                0x0a0000aa);

        // Now a packet sent from 0x0a0100cc to 0x0a0000aa should get all the
        // way through.
        ForwardInfo fInfo2 = prepareFwdInfo(ingrDevPort.getId(), eth);
        vrn.process(fInfo2);
        TestRouter.checkForwardInfo(fInfo2, Action.FORWARD, egrDevPort.getId(),
                0x0a0000aa);
        Assert.assertEquals(2, fInfo2.getNumFEsTraversed());
        Assert.assertEquals(1, fInfo2.getTimesTraversed(routerIds.get(0)));
        Assert.assertEquals(1, fInfo2.getTimesTraversed(routerIds.get(1)));
        Assert.assertEquals(0, fInfo2.getTimesTraversed(routerIds.get(2)));
    }

    @Test
    public void testThreeRoutersForward() throws StateAccessException,
            ZkStateSerializationException, IOException, JMException,
            KeeperException {
        // Send a packet to router1's second materialized port to a destination
        // reachable from router2's second materialized port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        L3DevicePort ingrDevPort = devPorts.get(3);
        L3DevicePort egrDevPort = devPorts.get(5);
        Ethernet eth = TestRouter.makeUDP(
                MAC.fromString("02:00:11:22:00:01"), ingrDevPort.getMacAddr(),
                0x0a0101bb, 0x0a020188, (short) 101, (short) 212, payload);
        ForwardInfo fInfo = prepareFwdInfo(ingrDevPort.getId(), eth);
        vrn.process(fInfo);
        Assert.assertEquals(3, fInfo.getNumFEsTraversed());
        Assert.assertEquals(1, fInfo.getTimesTraversed(routerIds.get(0)));
        Assert.assertEquals(1, fInfo.getTimesTraversed(routerIds.get(1)));
        Assert.assertEquals(1, fInfo.getTimesTraversed(routerIds.get(2)));
        // Packet gets stopped by final-hop ARP.
        Assert.assertEquals(Action.PAUSED, fInfo.action);
        Ethernet arpReply = TestRouter.makeArpReply(
                MAC.fromString("02:00:11:22:11:0b"), egrDevPort.getMacAddr(),
                0x0a020188, egrDevPort.getIPAddr());
        vrn.process(prepareFwdInfo(egrDevPort.getId(), arpReply));
        TestRouter.checkForwardInfo(fInfo, Action.FORWARD, egrDevPort.getId(),
                0x0a020188);
    }

    @Test @Ignore
    public void testArpRequestGeneration() throws ZkStateSerializationException {
        // Try to get the MAC for a nwAddr on router2's second port (i.e. in
        // 10.2.1.0/24).
        TestRouter.ArpCompletedCallback cb = new TestRouter.ArpCompletedCallback();
        L3DevicePort devPort = devPorts.get(5);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        //XXX vrn.getMacForIp(devPort.getId(), 0x0a020123, cb);
        // There should now be an ARP request in the MockProtocolStub
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(
                (short)0 /*devPort.getNum()*/, (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Ethernet expectedArp = TestRouter.makeArpRequest(devPort.getMacAddr(),
                devPort.getIPAddr(), 0x0a020123);
        Assert.assertArrayEquals(expectedArp.serialize(), pkt.data);
    }

    @Test
    public void testPortRemoval()
            throws StateAccessException, InterruptedException,
            IOException, JMException, KeeperException {
        // First remove a port without removing the ZK config.
        vrn.removePort(devPorts.get(0).getId());
        // Now test port removal when the ZK config has already been deleted.
        portMgr.delete(devPorts.get(1).getId());
        vrn.removePort(devPorts.get(1).getId());
        // Stuff should keep working.
        testThreeRoutersForward();
    }

    @Test @Ignore
    public void testJmxConnection()
            throws JMException, IOException, ZkStateSerializationException,
                   StateAccessException, KeeperException {
        JMXServiceURL url = new JMXServiceURL("service:jmx:rmi://");
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        JMXConnectorServer cs =
                JMXConnectorServerFactory.newJMXConnectorServer(url, null, mbs);
        cs.start();
        JMXConnector cc = null;
        try {
            JMXServiceURL addr = cs.getAddress();
            cc = JMXConnectorFactory.connect(addr);
            MBeanServerConnection mbsc = cc.getMBeanServerConnection();
            ObjectName oname =
                new ObjectName("com.midokura.midolman.layer3:type=Router,name="
                               + routerIds.get(2));
            Integer address = new Integer(0x0a020102);
            L3DevicePort devPort = devPorts.get(5);
            UUID portUuid = devPort.getId();
            Object[] ackParams = new Object[] { portUuid.toString() };
            String[] ackSignature = new String[] { "java.lang.String" };
            Object[] aceParams = new Object[] { portUuid.toString(), address };
            String[] aceSignature = new String[] { "java.lang.String", "int" };
            TabularData table = (TabularData)mbsc.getAttribute(oname, "PortSet");
            log.debug("table is {}", table);
            Collection<CompositeData> portSet =
                        (Collection<CompositeData>)table.values();
            log.debug("portUuid {}", portUuid);
            boolean foundPort = false;
            for (CompositeData port : portSet) {
                log.debug("portSet entry {}", port.get("string"));
                if (port.get("string").equals(portUuid.toString()))
                    foundPort = true;
            }
            Assert.assertTrue(foundPort);
            table = (TabularData)mbsc.invoke(oname, "getArpCacheKeys",
                                             ackParams, ackSignature);
            Assert.assertEquals(0, table.size());
            table = (TabularData)mbsc.invoke(oname, "getArpCacheTable",
                                             ackParams, ackSignature);
            Assert.assertEquals(0, table.size());
            // Construct an ARP reply for 0x0a020102 (10.2.1.2)
            MAC remoteMAC = new MAC(new byte[] { (byte) 10, (byte) 2, (byte) 1,
                                             (byte) 2, (byte) 3, (byte) 3 });
            Ethernet arpReply = TestRouter.makeArpReply(remoteMAC,
                        devPort.getMacAddr(), 0x0a020102, devPort.getIPAddr());
            ForwardInfo fInfo = prepareFwdInfo(devPort.getId(), arpReply);
            vrn.process(fInfo);
            table = (TabularData)mbsc.invoke(oname, "getArpCacheKeys",
                                             ackParams, ackSignature);
            Assert.assertEquals(1, table.size());
            CompositeData ipAddr = (CompositeData)table.values().toArray()[0];
            Assert.assertEquals(new Integer(0x0a020102), ipAddr.get("int"));
            Object rv = mbsc.invoke(oname, "getArpCacheEntry", aceParams,
                             aceSignature);
            Assert.assertTrue(((String)rv).startsWith(
                        "ArpCacheEntry [macAddr=0a:02:01:02:03:03"));
            table = (TabularData)mbsc.invoke(oname, "getArpCacheTable",
                                             ackParams, ackSignature);
            Assert.assertEquals(1, table.size());
            ipAddr = (CompositeData)table.values().toArray()[0];
            Assert.assertEquals(new Integer(0x0a020102), ipAddr.get("int"));
            Assert.assertTrue(((String)ipAddr.get("string")).startsWith(
                        "ArpCacheEntry [macAddr=0a:02:01:02:03:03"));
        } finally {
            if (cc != null)
                cc.close();
            cs.stop();
        }
    }

}
