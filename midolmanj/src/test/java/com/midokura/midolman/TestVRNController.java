/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.codehaus.jackson.JsonParseException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPortStatus;
import org.openflow.protocol.action.*;
import scala.actors.threadpool.Arrays;

import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.layer3.ReplicatedRoutingTable;
import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.layer3.Router;
import com.midokura.midolman.layer3.TestRouter;
import com.midokura.midolman.layer4.NatLeaseManager;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.openflow.nxm.NxActionSetTunnelKey32;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection.GrePort;
import com.midokura.midolman.packets.*;
import com.midokura.midolman.portservice.MockPortService;
import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.ForwardNatRule;
import com.midokura.midolman.rules.LiteralRule;
import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.rules.ReverseNatRule;
import com.midokura.midolman.rules.Rule;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.state.*;
import com.midokura.midolman.state.BgpZkManager.BgpConfig;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;
import com.midokura.midolman.state.RouterZkManager.RouterConfig;
import com.midokura.midolman.util.MockCache;
import com.midokura.midolman.util.Net;
import com.midokura.midolman.util.ShortUUID;
import com.midokura.midolman.VRNController.PacketContinuation;


public class TestVRNController {

    private String basePath;
    private Directory dir;
    private VRNController vrnCtrl;
    private short idleFlowTimeoutSeconds;
    private List<List<OFPhysicalPort>> phyPorts;
    private List<UUID> routerIds;
    private MockReactor reactor;
    private MockControllerStub controllerStub;
    private PortToIntNwAddrMap portLocMap;
    private Map<UUID, ArpTable> arpTables;
    private BgpZkManager bgpMgr;
    private PortZkManager portMgr;
    private RouteZkManager routeMgr;
    private ChainZkManager chainMgr;
    private RuleZkManager ruleMgr;
    private RouterZkManager routerMgr;
    private BridgeZkManager bridgeMgr;
    private MockCache cache;
    private int cacheExpireSecs; // This should be an even number.
    private MockOpenvSwitchDatabaseConnection ovsdb;
    private MockPortService bgpService;
    private MockPortService vpnService;
    private long datapathId;
    private OFPhysicalPort uplinkPhyPort;
    private UUID uplinkId;
    private int uplinkGatewayAddr;
    private int uplinkPortAddr;
    private UUID portOn0to2;
    private UUID portOn2to0;
    private int rtr2LogPortNwAddr;
    private int rtr0to2LogPortNwAddr;
    private MAC rtr2LogPortMAC;
    private MAC rtr0to2LogPortMAC;
    private Map<Short, UUID> portNumToUuid;
    private UUID bridgeID;
    private int bridgeGreKey;
    private short portNumA = 100;
    private short portNumB = 101;
    private short tunnelPortNumA = 102;
    private short tunnelPortNumB = 103;

    @Before
    public void setUp() throws Exception {
        phyPorts = new ArrayList<List<OFPhysicalPort>>();
        routerIds = new ArrayList<UUID>();
        portNumToUuid = new HashMap<Short, UUID>();
        arpTables = new HashMap<UUID, ArpTable>();
        reactor = new MockReactor();
        controllerStub = new MockControllerStub();

        basePath = "/midolman";
        ZkPathManager pathMgr = new ZkPathManager(basePath);
        dir = new MockDirectory();
        dir.add(pathMgr.getBasePath(), null, CreateMode.PERSISTENT);
        Setup.createZkDirectoryStructure(dir, basePath);
        portMgr = new PortZkManager(dir, basePath);
        routeMgr = new RouteZkManager(dir, basePath);
        chainMgr = new ChainZkManager(dir, basePath);
        ruleMgr = new RuleZkManager(dir, basePath);
        bgpMgr = new BgpZkManager(dir, basePath);
        routerMgr = new RouterZkManager(dir, basePath);
        bridgeMgr = new BridgeZkManager(dir, basePath);

        // Now build the network's port to location map.
        UUID networkId = new UUID(1, 1);
        Directory portLocSubdir =
                dir.getSubDirectory(pathMgr.getVRNPortLocationsPath());
        ((MockDirectory)portLocSubdir).enableDebugLog = true;
        portLocMap = new PortToIntNwAddrMap(portLocSubdir);
        portLocMap.start();
        PortSetMap portSetMap = new PortSetMap(dir, basePath);
        portSetMap.start();

        // Now create the Open vSwitch database connection
        ovsdb = new MockOpenvSwitchDatabaseConnection();

        // Now create the BGP PortService
        bgpService = new MockPortService(portMgr, bgpMgr);

        // Now create the VPN PortService
        vpnService = new MockPortService(portMgr, bgpMgr);

        // Now we can create the VRNController itself.
        IntIPv4 localNwIP = IntIPv4.fromString("192.168.1.4"); // 0xc0a80104
        datapathId = 43;
        // The mock cache has a 60 second expiration.
        cacheExpireSecs = 60; // Use an even number.
        idleFlowTimeoutSeconds = 20;
        cache = new MockCache(reactor, cacheExpireSecs);
        UUID vrnId = UUID.randomUUID();
        vrnCtrl = new VRNController(dir, basePath, localNwIP, ovsdb,
                reactor, cache, "midonet", vrnId, false, bgpService,
                vpnService);
        vrnCtrl.setControllerStub(controllerStub);
        vrnCtrl.setDatapathId(datapathId);
        vrnCtrl.portLocMap.start();

        /*
         * Create 3 routers such that:
         *    1) router0 handles traffic to 10.0.0.0/16
         *    2) router1 handles traffic to 10.1.0.0/16
         *    3) router2 handles traffic to 10.2.0.0/16
         *    4) router0 and router1 are connected via logical ports
         *    5) router0 and router2 are connected via logical ports
         *    6) router0 is the default gateway for router1 and router2
         *    7) router0 has a single uplink to the global internet.
         */
        Route rt;
        PortDirectory.MaterializedRouterPortConfig portConfig;
        List<ReplicatedRoutingTable> rTables =
                new ArrayList<ReplicatedRoutingTable>();
        for (int i = 0; i < 3; i++) {
            phyPorts.add(new ArrayList<OFPhysicalPort>());
            UUID rtrId = routerMgr.create();
            routerIds.add(rtrId);
            Directory arpTableDir =
                    routerMgr.getArpTableDirectory(rtrId);
            ArpTable arpTable = new ArpTable(arpTableDir);
            arpTable.start();
            arpTables.put(rtrId, arpTable);

            Directory tableDir = routerMgr.getRoutingTableDirectory(rtrId);
            // Note: we keep our own copy of the replicated routing tables
            // so that we don't have to modify the internal ones.
            ReplicatedRoutingTable rTable = new ReplicatedRoutingTable(rtrId,
                    tableDir, CreateMode.PERSISTENT);
            rTables.add(rTable);

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
                        (byte) 0xee, (byte) 0xdd, (byte) 0xcc, (byte) 0xff,
                        (byte) portNum });
                portConfig = new PortDirectory.MaterializedRouterPortConfig(
                        rtrId, portNw, 24, portAddr, mac, null, portNw, 24,
                        null);
                UUID portId = portMgr.create(portConfig);
                rt = new Route(0, 0, portNw, 24, NextHop.PORT, portId,
                        Route.NO_GATEWAY, 2, null, rtrId);
                routeMgr.create(rt);

                OFPhysicalPort phyPort = new OFPhysicalPort();
                phyPorts.get(i).add(phyPort);
                phyPort.setPortNumber(portNum);
                portNumToUuid.put(new Short(portNum), portId);
                phyPort.setHardwareAddress(mac.getAddress());
                IntIPv4 underlayIp;
                if (0 == portNum % 2) {
                    // Even-numbered ports will be local to the controller.
                    ovsdb.setPortExternalId(datapathId, portNum, "midonet",
                            portId.toString());
                    phyPort.setName("port" + Integer.toString(portNum));
                    underlayIp = localNwIP;
                } else {
                    // Odd-numbered ports are remote. Place port num x at
                    // 192.168.1.x.
                    underlayIp = new IntIPv4(0xc0a80100 + portNum);
                    portLocMap.put(portId, underlayIp);
                    // The new port id in portLocMap should have resulted
                    // in a call to to the mock ovsdb to open a gre port.
                    phyPort.setName(vrnCtrl.makeGREPortName(underlayIp));
                    GrePort expectGrePort = new GrePort(
                            Long.toString(datapathId), phyPort.getName(),
                            underlayIp.toString());
                    Assert.assertEquals(expectGrePort,
                            ovsdb.addedGrePorts.get(
                                        ovsdb.addedGrePorts.size() - 1));
                    // Manually add the remote port's route since we're only
                    // pretending there are remote controllers.
                    rTable.addRoute(rt);
                }
                vrnCtrl.onPortStatus(phyPort,
                        OFPortStatus.OFPortReason.OFPPR_ADD);
                // Verify that the port location map is correctly initialized.
                Assert.assertEquals(underlayIp, portLocMap.get(portId));
            }
        }

        // TODO(pino, dan): fix this.
        // Two flows should have been installed for each locally added port.
        Assert.assertEquals(6, controllerStub.addedFlows.size());
        // Clear the flows: unit-tests assume the addedFlows queue starts empty.
        controllerStub.addedFlows.clear();

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
        rt = new Route(0, 0, 0, 0, NextHop.PORT, portOn1to0, 0xc0a80101, 10,
                null, routerIds.get(1));
        routeMgr.create(rt);
        // Now add the logical links between router 0 and 2.
        // First from 0 to 2
        rtr0to2LogPortNwAddr = 0xc0a80101;
        rtr2LogPortNwAddr = 0xc0a80102;
        logPortConfig1 = new PortDirectory.LogicalRouterPortConfig(
                routerIds.get(0), 0xc0a80100, 30, rtr0to2LogPortNwAddr, null,
                null, null);
        logPortConfig2 = new PortDirectory.LogicalRouterPortConfig(
                routerIds.get(2), 0xc0a80100, 30, rtr2LogPortNwAddr, null,
                null, null);
        idPair = portMgr.createLink(logPortConfig1, logPortConfig2);
        portOn0to2 = idPair.key;
        portOn2to0 = idPair.value;
        RouterPortConfig pcfg = (RouterPortConfig)portMgr.get(portOn0to2).value;
        rtr0to2LogPortMAC = pcfg.hwAddr;
        pcfg = (RouterPortConfig)portMgr.get(portOn2to0).value;
        rtr2LogPortMAC = pcfg.hwAddr;
        rt = new Route(0, 0, 0x0a020000, 16, NextHop.PORT, portOn0to2,
                0xc0a80102, 2, null, routerIds.get(0));
        routeMgr.create(rt);
        // Now from 2 to 0. Note that this is router2's uplink.
        rt = new Route(0, 0, 0, 0, NextHop.PORT, portOn2to0, 0xc0a80101, 10,
                null, routerIds.get(2));
        routeMgr.create(rt);

        // For now, don't add an uplink. Instead add a route that drops anything
        // in 10.0.0.0/8 that isn't going to router0's local or logical ports.
        rt = new Route(0, 0, 0x0a000000, 8, NextHop.BLACKHOLE, null, 0, 2,
                null, routerIds.get(0));
        routeMgr.create(rt);

        // Create a Bridge with two local and two remote ports.
        byte HWaddr[] = new byte[] { (byte)2, (byte)22, (byte)222,
                                     (byte)1, (byte)10, (byte)portNumA };
        BridgeZkManager.BridgeConfig brcfg =
                new BridgeZkManager.BridgeConfig(null, null);
        bridgeID = bridgeMgr.create(brcfg);
        bridgeGreKey = brcfg.greKey;
        Assert.assertTrue(bridgeGreKey > 0);
        PortDirectory.BridgePortConfig bridgePortConfig =
                new PortDirectory.BridgePortConfig(bridgeID);
        UUID portID = portMgr.create(bridgePortConfig);
        OFPhysicalPort phyPort = new OFPhysicalPort();
        phyPort.setPortNumber(portNumA);
        phyPort.setHardwareAddress(HWaddr);
        portNumToUuid.put(portNumA, portID);
        ovsdb.setPortExternalId(datapathId, portNumA, "midonet",
                                portID.toString());
        phyPort.setName("bridge_port_a");
        vrnCtrl.onPortStatus(phyPort, OFPortStatus.OFPortReason.OFPPR_ADD);

        bridgePortConfig = new PortDirectory.BridgePortConfig(bridgeID);
        portID = portMgr.create(bridgePortConfig);
        phyPort = new OFPhysicalPort();
        phyPort.setPortNumber(portNumB);
        HWaddr[5] = (byte)portNumB;
        phyPort.setHardwareAddress(HWaddr);
        portNumToUuid.put(portNumB, portID);
        ovsdb.setPortExternalId(datapathId, portNumB, "midonet",
                                portID.toString());
        phyPort.setName("bridge_port_b");
        vrnCtrl.onPortStatus(phyPort, OFPortStatus.OFPortReason.OFPPR_ADD);

        // Now two remote ports.
        IntIPv4 addr = IntIPv4.fromString("192.168.2.100");
        bridgePortConfig = new PortDirectory.BridgePortConfig(bridgeID);
        portID = portMgr.create(bridgePortConfig);
        portLocMap.put(portID, addr);
        // Normally, the remote controller would add the port to the bridge's
        // port set, but here we have to do it.
        portSetMap.addIPv4Addr(bridgeID, addr);
        phyPort = new OFPhysicalPort();
        phyPort.setName(vrnCtrl.makeGREPortName(addr));
        HWaddr[5] = (byte)210;
        phyPort.setHardwareAddress(HWaddr);
        phyPort.setPortNumber(tunnelPortNumA);
        vrnCtrl.onPortStatus(phyPort, OFPortStatus.OFPortReason.OFPPR_ADD);

        addr = IntIPv4.fromString("192.168.2.110");
        bridgePortConfig = new PortDirectory.BridgePortConfig(bridgeID);
        portID = portMgr.create(bridgePortConfig);
        portLocMap.put(portID, addr);
        portSetMap.addIPv4Addr(bridgeID, addr);
        phyPort = new OFPhysicalPort();
        phyPort.setName(vrnCtrl.makeGREPortName(addr));
        HWaddr[5] = (byte)211;
        phyPort.setHardwareAddress(HWaddr);
        phyPort.setPortNumber(tunnelPortNumB);
        vrnCtrl.onPortStatus(phyPort, OFPortStatus.OFPortReason.OFPPR_ADD);

        // Two flows should have been installed for each locally added port.
        // (One's the tunnel, the other is DHCP.)
        Assert.assertEquals(4, controllerStub.addedFlows.size());
        // Clear the flows: unit-tests assume the addedFlows queue starts empty.
        controllerStub.addedFlows.clear();
    }

    public static void checkInstalledFlow(MockControllerStub.Flow flow,
            OFMatch match, short idleTimeoutSecs, short hardTimeoutSecs,
            int bufferId, boolean sendFlowRemove, List<OFAction> actions) {
        Assert.assertEquals(match, flow.match);
        Assert.assertEquals(idleTimeoutSecs, flow.idleTimeoutSecs);
        Assert.assertEquals(hardTimeoutSecs, flow.hardTimeoutSecs);
        Assert.assertEquals(bufferId, flow.bufferId);
        Assert.assertEquals(sendFlowRemove, flow.sendFlowRemove);
        Assert.assertEquals(actions.size(), flow.actions.size());
        for (int i = 0; i < actions.size(); i++)
            Assert.assertTrue(actions.get(i).equals(flow.actions.get(i)));
    }

    @Test
    public void testPacketToUnrecognizedPort() {
        // Verify that if a packet arrives on a port that is neither a tunnel
        // nor a virtual port, the packet is dropped.
        Ethernet eth = TestRouter.makeUDP(MAC.fromString("02:00:11:22:00:01"),
                MAC.fromString("02:00:11:22:00:01"), 0x0a000005, 0x0a040005,
                (short) 101, (short) 212, new byte[] {4, 5, 6, 7, 8});
        byte[] data = eth.serialize();
        // Send to a non-existent port.
        vrnCtrl.onPacketIn(55, data.length, (short)1234, data);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        // Verify it's installed a drop rule.
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = new MidoMatch();
        match.setInputPort((short)1234);
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                (short)0, vrnCtrl.TEMPORARY_DROP_SECONDS, 55, false,
                new ArrayList<OFAction>());
    }

    @Test
    public void testWrongMAC() {
        // Send a packet to a MAC which isn't the router's.
        // Should be ignored (ie, dropped): Maybe from a physical learning
        // switch or a hub.
        byte[] payload = { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPort = phyPorts.get(0).get(0);
        Ethernet eth = TestRouter.makeUDP(MAC.fromString("02:31:41:51:61:01"),
                MAC.fromString("02:31:41:51:61:f1"),
                0x0a000005, 0x0a000004, (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        vrnCtrl.onPacketIn(55, data.length, phyPort.getPortNumber(), data);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, phyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                VRNController.NO_IDLE_TIMEOUT,
                VRNController.TEMPORARY_DROP_SECONDS, 55, false, actions);
    }

    @Test
    public void testBridgeFlood()
            throws StateAccessException, RuleIndexOutOfBoundsException {
        // No MAC are known at startup, so any MAC should flood.
        Ethernet eth = TestRouter.makeUDP(MAC.fromString("02:00:11:22:00:01"),
                           MAC.fromString("02:00:11:22:00:12"), 0x0a000005,
                           0x0a040005, (short) 101, (short) 212,
                           new byte[] {4, 5, 6, 7, 8});
        byte[] data = eth.serialize();
        // Send to port A.
        vrnCtrl.onPacketIn(-1, data.length, portNumA, data);
        // Check that it flooded.
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        // The output actions can be in any order; use the one actually chosen.
        List<OFAction> expectActions = new ArrayList<OFAction>();
        expectActions.add(new NxActionSetTunnelKey32(bridgeGreKey));
        expectActions.add(new OFActionOutput(tunnelPortNumA, (short)0));
        expectActions.add(new OFActionOutput(tunnelPortNumB, (short)0));
        expectActions.add(new OFActionOutput(portNumB, (short)0));
        MockControllerStub.Packet actualPacket =
                controllerStub.sentPackets.get(0);
        Assert.assertEquals(-1, actualPacket.bufferId);
        Assert.assertArrayEquals(data, actualPacket.data);
        Assert.assertArrayEquals(expectActions.toArray(),
                                 actualPacket.actions.toArray());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match =
                AbstractController.createMatchFromPacket(eth, portNumA);
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                -1, true, expectActions);

        // Add a chain on port B which drops, re-do, verify it's sent to
        // the two tunnel ports, but not to port B.
        UUID chainUuid = chainMgr.create(new ChainConfig("TEST"));
        Rule dropRule = new LiteralRule(new Condition(), Action.DROP, chainUuid,
                                        1);
        ruleMgr.create(dropRule);
        PortDirectory.BridgePortConfig bridgePortConfig =
                new PortDirectory.BridgePortConfig(bridgeID);
        bridgePortConfig.outboundFilter = chainUuid;
        portMgr.update(new ZkNodeEntry<UUID, PortConfig>(
                           portNumToUuid.get(portNumB), bridgePortConfig));
        // Send to port A.
        vrnCtrl.onPacketIn(1, data.length, portNumA, data);
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        expectActions.remove(3);
        checkInstalledFlow(controllerStub.addedFlows.get(1), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                1, true, expectActions);
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        actualPacket = controllerStub.sentPackets.get(1);
        Assert.assertEquals(1, actualPacket.bufferId);
        // Empty data array due to packet being buffered.
        Assert.assertArrayEquals(new byte[]{}, actualPacket.data);
        Assert.assertArrayEquals(expectActions.toArray(),
                                 actualPacket.actions.toArray());
    }

    @Test
    public void testFloodEgress()
            throws StateAccessException, RuleIndexOutOfBoundsException {
        Ethernet eth = TestRouter.makeUDP(MAC.fromString("02:00:11:22:00:01"),
                           MAC.fromString("02:00:11:22:00:12"), 0x0a000005,
                           0x0a040005, (short) 101, (short) 212,
                           new byte[] {4, 5, 6, 7, 8});
        byte[] data = eth.serialize();
        // Send to tunnel A.
        vrnCtrl.onPacketIn(-1, data.length, tunnelPortNumA, data, bridgeGreKey);

        // Verify it flooded to ports A and B.
        MidoMatch match = AbstractController.createMatchFromPacket(
                              eth, tunnelPortNumA);
        // The output actions can be in any order; use the one actually chosen.
        List<OFAction> expectActions = new ArrayList<OFAction>();
        expectActions.add(new OFActionOutput(portNumA, (short)0));
        expectActions.add(new OFActionOutput(portNumB, (short)0));

        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                -1, false, expectActions);
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet actualPacket =
                controllerStub.sentPackets.get(0);
        Assert.assertEquals(-1, actualPacket.bufferId);
        Assert.assertArrayEquals(data, actualPacket.data);
        Assert.assertArrayEquals(expectActions.toArray(),
                                 actualPacket.actions.toArray());

        // Add a chain on port A which drops, re-do, verify it's sent to
        // port B only.
        UUID chainUuid = chainMgr.create(new ChainConfig("TEST"));
        Rule dropRule = new LiteralRule(new Condition(), Action.DROP, chainUuid,
                                        1);
        ruleMgr.create(dropRule);
        PortDirectory.BridgePortConfig bridgePortConfig =
                new PortDirectory.BridgePortConfig(bridgeID);
        bridgePortConfig.outboundFilter = chainUuid;
        portMgr.update(new ZkNodeEntry<UUID, PortConfig>(
                           portNumToUuid.get(portNumA), bridgePortConfig));

        // Send to tunnel A.
        vrnCtrl.onPacketIn(-1, data.length, tunnelPortNumA, data, bridgeGreKey);

        // Verify it flooded to port B only.
        expectActions.remove(0);
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        checkInstalledFlow(controllerStub.addedFlows.get(1), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                -1, false, expectActions);
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        actualPacket = controllerStub.sentPackets.get(1);
        Assert.assertEquals(-1, actualPacket.bufferId);
        Assert.assertArrayEquals(data, actualPacket.data);
        Assert.assertArrayEquals(expectActions.toArray(),
                                 actualPacket.actions.toArray());
    }

    @Test
    public void testOneRouterBlackhole() {
        // Send a packet to router0's first materialized port to a destination
        // that's blackholed.
        byte[] payload = { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPort = phyPorts.get(0).get(0);
        Ethernet eth = TestRouter.makeUDP(MAC.fromString("02:00:11:22:00:01"),
                new MAC(phyPort.getHardwareAddress()), 0x0a000005, 0x0a040005,
                (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        vrnCtrl.onPacketIn(55, data.length, phyPort.getPortNumber(), data);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, phyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                VRNController.NO_IDLE_TIMEOUT,
                VRNController.TEMPORARY_DROP_SECONDS, 55, false, actions);
    }

    @Test
    public void testOneRouterPktConsumed() {
        // Send an ARP to router0's first materialized port. Note any ARP will
        // be consumed (but possibly not replied to).
        Ethernet eth = TestRouter.makeArpRequest(
                MAC.fromString("02:aa:bb:aa:bb:0c"), 0x01234567, 0x76543210);
        byte[] data = eth.serialize();
        OFPhysicalPort phyPort = phyPorts.get(0).get(0);
        vrnCtrl.onPacketIn(1001, data.length, phyPort.getPortNumber(), data);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(1, controllerStub.droppedPktBufIds.size());
        Assert.assertTrue(1001 == controllerStub.droppedPktBufIds.get(0));

        // Try again, this time with an unbuffered packet:
        vrnCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, data.length,
                phyPort.getPortNumber(), data);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        // No new call to stub since nothing to be done to free unbuffered pkt.
        Assert.assertEquals(1, controllerStub.droppedPktBufIds.size());
    }

    @Test
    public void testOneRouterNotIPv4() {
        // This isn't a real IPv6 packet. So this will break if we add an
        // IPv6 class in com.midokura.midolman.packets.
        Data payload = new Data();
        ByteBuffer bb = ByteBuffer.wrap(new byte[100], 0, 100);
        payload.deserialize(bb);
        OFPhysicalPort phyPort = phyPorts.get(0).get(0);
        MAC dstMac = new MAC(phyPort.getHardwareAddress());
        Ethernet eth = new Ethernet();
        eth.setSourceMACAddress(MAC.fromString("02:ab:cd:ef:01:23"));
        eth.setDestinationMACAddress(dstMac);
        eth.setEtherType((short) 0x86dd); // IPv6
        byte[] data = eth.serialize();
        vrnCtrl.onPacketIn(123456, data.length, phyPort.getPortNumber(), data);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MidoMatch match = new MidoMatch();
        match.setDataLayerType((short) 0x86dd);
        match.setDataLayerDestination(dstMac);
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                VRNController.NO_IDLE_TIMEOUT,
                VRNController.NO_HARD_TIMEOUT, 123456, false, actions);
    }

    @Test
    public void testOneRouterNoRoute() throws Exception {
        // Send a packet to router0's first materialized port to a destination
        // that has no route.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPort = phyPorts.get(0).get(0);
        MAC mac = MAC.fromString("02:00:11:22:00:01");
        Ethernet eth = TestRouter.makeUDP(mac,
                new MAC(phyPort.getHardwareAddress()), 0x0a000005, 0x0b000005,
                (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        vrnCtrl.onPacketIn(565656, data.length, phyPort.getPortNumber(), data);
        // This time along with the 'drop' flow, we expect an ICMP !N addressed
        // to the source of the UDP.
        Assert.assertEquals(1, reactor.calls.size());
        Assert.assertTrue(reactor.calls.peek().runnable instanceof
                          PacketContinuation);
        PacketContinuation pktCont =
            (PacketContinuation) reactor.calls.peek().runnable;
        // Check pktCont.fwdInfo
        Assert.assertEquals(null, pktCont.fwdInfo.inPortId);
        Assert.assertEquals(portNumToUuid.get(phyPort.getPortNumber()),
                            pktCont.fwdInfo.outPortId);
        checkICMP(ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toChar(),
                IPv4.class.cast(eth.getPayload()),
                new MAC(phyPort.getHardwareAddress()), mac, 0x0a000001,
                0x0a000005, pktCont.fwdInfo.pktIn.serialize());

        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, phyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                VRNController.NO_IDLE_TIMEOUT,
                VRNController.TEMPORARY_DROP_SECONDS, 565656, false, actions);
    }

    @Test
    public void testOneRouterReject() throws Exception {
        // Send a packet to router1's first materialized port to a destination
        // that will be rejected (in 10.1.0.0/16, not in 10.1.<0 or 1>.0/24).
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPort = phyPorts.get(1).get(0);
        MAC mac = MAC.fromString("02:00:11:22:00:01");
        Ethernet eth = TestRouter.makeUDP(mac,
                new MAC(phyPort.getHardwareAddress()), 0x0a010005, 0x0a010305,
                (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        // Test de-serialization by padding the data array with extra bytes.
        data = Arrays.copyOf(data, data.length + 3);
        vrnCtrl.onPacketIn(11111, data.length, phyPort.getPortNumber(),
                data);
        // Along with the "drop" flow, we expect an ICMP !X, to be queued
        // in the reactor's DelayedCall queue as an GeneratedPacketContext.
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(1, reactor.calls.size());
        Assert.assertTrue(reactor.calls.peek().runnable instanceof
                          PacketContinuation);
        PacketContinuation pktCont =
            (PacketContinuation) reactor.calls.peek().runnable;
        Assert.assertEquals(null, pktCont.fwdInfo.inPortId);
        Assert.assertEquals(portNumToUuid.get(phyPort.getPortNumber()),
                            pktCont.fwdInfo.outPortId);
        checkICMP(ICMP.TYPE_UNREACH,
                ICMP.UNREACH_CODE.UNREACH_FILTER_PROHIB.toChar(),
                IPv4.class.cast(eth.getPayload()),
                new MAC(phyPort.getHardwareAddress()), mac, 0x0a010001,
                0x0a010005, pktCont.fwdInfo.pktIn.serialize());

        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, phyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                VRNController.NO_IDLE_TIMEOUT,
                VRNController.TEMPORARY_DROP_SECONDS, 11111, false, actions);
    }

    @Test
    public void testMultipleRoutersLocalOutput() {
        // Send a packet to router1's first port to an address on router2's
        // first port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortIn = phyPorts.get(1).get(0);
        OFPhysicalPort phyPortOut = phyPorts.get(2).get(0);
        Ethernet eth = TestRouter.makeUDP(MAC.fromString("02:00:11:22:00:01"),
                new MAC(phyPortIn.getHardwareAddress()), 0x0a010005,
                0x0a020008, (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        vrnCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, data.length,
                phyPortIn.getPortNumber(), data);
        // router2 will have to ARP, so no flows installed yet, but one
        // unbuffered packet should have been pended.
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        // Expect 3 delayed calls: Send ARP, retry ARP, expire ARP.
        Assert.assertEquals(3, reactor.calls.size());
        Assert.assertTrue(reactor.calls.peek().runnable instanceof
                          PacketContinuation);
        PacketContinuation pktCont =
            (PacketContinuation) reactor.calls.peek().runnable;
        // Check pktCont.fwdInfo
        Assert.assertEquals(null, pktCont.fwdInfo.inPortId);
        Assert.assertEquals(portNumToUuid.get(phyPortOut.getPortNumber()),
                            pktCont.fwdInfo.outPortId);
        byte[] arpData = TestRouter.makeArpRequest(
                new MAC(phyPortOut.getHardwareAddress()), 0x0a020001,
                0x0a020008).serialize();
        byte[] pktData = pktCont.fwdInfo.pktIn.serialize();
        System.out.print("|");
        for (byte b : arpData)
            System.out.printf(" %02X", b);
        System.out.print("|\n|");
        for (byte b : pktData)
            System.out.printf(" %02X", b);
        System.out.print("|\n");
        Assert.assertArrayEquals(arpData, pktCont.fwdInfo.pktIn.serialize());

        // Poke the reactor to drain the queued packet (ARP request).
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(phyPortOut.getPortNumber(),
                (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. The flow should be installed as a result,
        // and since the original packet was unbuffered, there should be an
        // additional packet in the sentPackets queue. Finally, this ARP reply
        // itself will be consumed, and since it's buffered, its bufferId will
        // should appear in the droppedPktBufIds list.
        MAC mac = MAC.fromString("02:dd:dd:dd:dd:01");
        arpData = TestRouter.makeArpReply(mac,
                new MAC(phyPortOut.getHardwareAddress()), 0x0a020008,
                0x0a020001).serialize();
        vrnCtrl.onPacketIn(8765, arpData.length,
                phyPortOut.getPortNumber(), arpData);
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(1, controllerStub.droppedPktBufIds.size());
        Assert.assertTrue(8765 == controllerStub.droppedPktBufIds.get(0));
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, phyPortIn.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(
                phyPortOut.getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(
                mac.getAddress());
        actions.add(ofAction);
        actions.add(tmp); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                ControllerStub.UNBUFFERED_ID, false, actions);

        Assert.assertEquals(2, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(1);
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertEquals(OFPort.OFPP_NONE.getValue(), pkt.inPort);
        Assert.assertTrue(Arrays.equals(data, pkt.data));
        Assert.assertEquals(3, pkt.actions.size());
        for (int i = 0; i < 3; i++)
            Assert.assertTrue(actions.get(i).equals(pkt.actions.get(i)));

        // Send a packet to router0's first port to the same address on
        // router2's first port.  No ARP will be needed this time so the
        // flow gets installed immediately.  No additional sent/dropped packets.
        phyPortIn = phyPorts.get(0).get(0);
        eth = TestRouter.makeUDP(MAC.fromString("02:44:33:ff:22:01"),
                new MAC(phyPortIn.getHardwareAddress()), 0x0a0000d4,
                0x0a020008, (short) 101, (short) 212, payload);
        data = eth.serialize();
        vrnCtrl.onPacketIn(9896, data.length, phyPortIn.getPortNumber(), data);
        // Assert.assertEquals(2, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        match = AbstractController.createMatchFromPacket(
                eth, phyPortIn.getPortNumber());
        checkInstalledFlow(controllerStub.addedFlows.get(1), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                9896, false, actions);
    }

    @Test
    public void testOneRouterOutputRemote()
            throws StateAccessException, KeeperException, InterruptedException {
        // Send a packet from router2's first port to an address on router2's
        // second port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortIn = phyPorts.get(2).get(0);
        OFPhysicalPort phyPortOut = phyPorts.get(2).get(1);
        UUID egressUuid = portNumToUuid.get((short)21);
        Ethernet eth = TestRouter.makeUDP(MAC.fromString("02:00:11:22:00:01"),
                new MAC(phyPortIn.getHardwareAddress()), 0x0a020012,
                0x0a020145, (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        vrnCtrl.onPacketIn(999, data.length, phyPortIn.getPortNumber(), data);
        // No packets dropped, and the buffered packet paused on an ARP.
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        // 3 delayed calls:  Send ARP, retry ARP, expire ARP.
        Assert.assertEquals(3, reactor.calls.size());
        Assert.assertTrue(reactor.calls.peek().runnable instanceof
                          PacketContinuation);
        PacketContinuation pktCont =
            (PacketContinuation) reactor.calls.peek().runnable;
        Assert.assertEquals(null, pktCont.fwdInfo.inPortId);
        Assert.assertEquals(egressUuid, pktCont.fwdInfo.outPortId);
        MAC srcMac = new MAC(phyPortOut.getHardwareAddress());
        Ethernet arp = TestRouter.makeArpRequest(srcMac, 0x0a020101, 0x0a020145);
        Assert.assertEquals(arp, pktCont.fwdInfo.pktIn);

        // "Answer" the ARP.  Because the output port is supposedly remote,
        // this means to update the ARP cache through the Directory.
        // TODO: Shouldn't there be a test for this in TestRouter?
        long now = reactor.currentTimeMillis();
        MAC dstMac = MAC.fromString("02:dd:dd:dd:0d:a1");
        arpTables.get(routerIds.get(2)).put(
                new IntIPv4(0x0a020145), new ArpCacheEntry(
                        dstMac, now + Router.ARP_TIMEOUT_MILLIS,
                        now + Router.ARP_RETRY_MILLIS, now));
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, phyPortIn.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        OFActionDataLayerSource ofDlSrcAction = new OFActionDataLayerSource();
        ofDlSrcAction.setDataLayerAddress(srcMac.getAddress());
        actions.add(ofDlSrcAction);
        OFActionDataLayerDestination ofDlDstAction =
                new OFActionDataLayerDestination();
        ofDlDstAction.setDataLayerAddress(dstMac.getAddress());
        actions.add(ofDlDstAction);
        // Set the tunnel ID to indicate the output port to use.
        ZkNodeEntry<UUID, PortConfig> tunnelIdEntry = portMgr.get(egressUuid);
        int tunnelId = tunnelIdEntry.value.greKey;
        NxActionSetTunnelKey32 ofTunnelIdAction =
                new NxActionSetTunnelKey32(tunnelId);
        actions.add(ofTunnelIdAction);
        // Router2's second port is reachable via the tunnel OF port number 21.
        OFActionOutput ofOutAction = new OFActionOutput((short) 21, (short) 0);
        actions.add(ofOutAction); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT, 999,
                false, actions);
    }

    @Test
    public void testThreeRouterOutputRemote()
            throws StateAccessException, KeeperException, InterruptedException {
        // Send a packet to router1's first port to an address on router2's
        // second port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd };
        OFPhysicalPort phyPortIn = phyPorts.get(1).get(0);
        OFPhysicalPort phyPortOut = phyPorts.get(2).get(1);
        UUID egressUuid = portNumToUuid.get((short)21);
        Ethernet eth = TestRouter.makeUDP(MAC.fromString("02:00:11:22:00:01"),
                new MAC(phyPortIn.getHardwareAddress()), 0x0a0100c5,
                0x0a0201e4, (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        vrnCtrl.onPacketIn(37654, data.length, phyPortIn.getPortNumber(),
                data);
        // No packets dropped, and the buffered packet pended on ARP.
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        // 3 calls:  Send ARP, retry ARP, expire ARP.
        Assert.assertEquals(3, reactor.calls.size());
        Assert.assertTrue(reactor.calls.peek().runnable instanceof
                          PacketContinuation);
        PacketContinuation pktCont =
            (PacketContinuation) reactor.calls.peek().runnable;
        Assert.assertEquals(null, pktCont.fwdInfo.inPortId);
        Assert.assertEquals(egressUuid, pktCont.fwdInfo.outPortId);
        MAC srcMac = new MAC(phyPortOut.getHardwareAddress());
        Ethernet arp = TestRouter.makeArpRequest(srcMac, 0x0a020101, 0x0a0201e4);
        Assert.assertEquals(arp, pktCont.fwdInfo.pktIn);
        // "Answer" the ARP.  Because the output port is supposedly remote,
        // this means to update the ARP cache through the Directory.
        Directory arpTableDir = routerMgr.getArpTableDirectory(routerIds.get(2));
        ArpTable arpTable = new ArpTable(arpTableDir);
        arpTable.start();
        long now = reactor.currentTimeMillis();
        MAC dstMac = MAC.fromString("02:dd:dd:dd:3d:a3");
        arpTable.put(new IntIPv4(0x0a0201e4), new ArpCacheEntry(
                        dstMac, now + Router.ARP_TIMEOUT_MILLIS,
                        now + Router.ARP_RETRY_MILLIS, now));
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, phyPortIn.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        OFActionDataLayerSource ofDlSrcAction = new OFActionDataLayerSource();
        ofDlSrcAction.setDataLayerAddress(srcMac.getAddress());
        actions.add(ofDlSrcAction);
        OFActionDataLayerDestination ofDlDstAction =
                new OFActionDataLayerDestination();
        ofDlDstAction.setDataLayerAddress(dstMac.getAddress());
        actions.add(ofDlDstAction);
        ZkNodeEntry<UUID, PortConfig> tunnelIdEntry = portMgr.get(egressUuid);
        int tunnelId = tunnelIdEntry.value.greKey;
        NxActionSetTunnelKey32 ofTunnelIdAction =
                new NxActionSetTunnelKey32(tunnelId);
        actions.add(ofTunnelIdAction);
        // Router2's second port is reachable via the tunnel OF port number 21.
        OFActionOutput ofOutAction = new OFActionOutput((short) 21, (short) 0);
        actions.add(ofOutAction); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                37654, false, actions);
    }

    @Test
    public void testRemoteOutputTunnelDown() throws Exception {
        // First, with the tunnel up.
        // Send a packet to router1's first port destined to an address on
        // router2's second port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd };
        OFPhysicalPort phyPortIn = phyPorts.get(1).get(0);
        OFPhysicalPort phyPortOut = phyPorts.get(2).get(1);
        MAC dlSrc = MAC.fromString("02:00:11:22:00:01");
        int nwSrc = 0x0a0100c5;
        int nwDst = 0x0a0201e4;
        Ethernet eth = TestRouter.makeUDP(dlSrc,
                new MAC(phyPortIn.getHardwareAddress()), nwSrc, nwDst,
                (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        // Pre-seed the ARP cache
        long now = reactor.currentTimeMillis();
        MAC dstMac = MAC.fromString("02:00:11:22:00:02");
        arpTables.get(routerIds.get(2)).put(
                new IntIPv4(0x0a0201e4), new ArpCacheEntry(
                dstMac, now + Router.ARP_TIMEOUT_MILLIS,
                now + Router.ARP_RETRY_MILLIS, now));
        vrnCtrl.onPacketIn(22333, data.length, phyPortIn.getPortNumber(),
                data);
        // A flow was installed and the packet was forwarded.
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, phyPortIn.getPortNumber());

        // The action list should include setting the L2 addresses.
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction action = new OFActionDataLayerSource();
        ((OFActionDataLayerSource)action).setDataLayerAddress(
                phyPortOut.getHardwareAddress());
        actions.add(action);
        action = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination)action).setDataLayerAddress(
                dstMac.getAddress());
        actions.add(action);
        // There should be an action setting the GRE tunnel id.
        UUID egressUuid = portNumToUuid.get((short)21);
        ZkNodeEntry<UUID, PortConfig> tunnelIdEntry = portMgr.get(egressUuid);
        int tunnelId = tunnelIdEntry.value.greKey;
        NxActionSetTunnelKey32 ofTunnelIdAction =
                new NxActionSetTunnelKey32(tunnelId);
        actions.add(ofTunnelIdAction);
        // the Output action goes at the end.
        actions.add(new OFActionOutput(phyPortOut.getPortNumber(), (short) 0));
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                22333, false, actions);

        // Now bring the tunnel down.
        vrnCtrl.onPortStatus(phyPortOut,
                OFPortStatus.OFPortReason.OFPPR_DELETE);
        // Send the packet again.
        vrnCtrl.onPacketIn(22111, data.length, phyPortIn.getPortNumber(),
                data);
        // Since the tunnel is down and the ARP is already resolved, a temporary
        // drop flow should have been installed. However, no ICMP error message
        // is generated.
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        // Now check the Drop Flow.
        checkInstalledFlow(controllerStub.addedFlows.get(1), match,
                VRNController.NO_IDLE_TIMEOUT,
                VRNController.TEMPORARY_DROP_SECONDS, 22111, false,
                new ArrayList<OFAction>());
    }

    @Test
    public void testDontSendICMP() throws StateAccessException {
        // Only IPv4 packets trigger ICMPs.
        Ethernet eth = new Ethernet();
        MAC dlSrc = MAC.fromString("02:aa:aa:aa:aa:01");
        MAC dlDst = MAC.fromString("02:aa:aa:aa:aa:23");
        eth.setDestinationMACAddress(dlDst);
        eth.setSourceMACAddress(dlSrc);
        eth.setEtherType(ARP.ETHERTYPE);
        Router rtr = new Router(
                routerIds.get(2), dir, basePath, reactor, null, null);
        Assert.assertFalse(rtr.canSendICMP(eth, null));

        // Make a normal UDP packet from a host on router2's second port
        // to a host on router0's second port. This can trigger ICMP.
        short dstPort = 1;
        UUID dstPortId = portNumToUuid.get(dstPort);
        int nwSrc = 0x0a020109;
        int nwDst = 0x0a00010d;
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        eth = TestRouter.makeUDP(dlSrc, dlDst, nwSrc, nwDst, (short) 2345,
                (short) 1221, payload);
        Assert.assertTrue(rtr.canSendICMP(eth, null));
        Assert.assertTrue(rtr.canSendICMP(eth, dstPortId));

        // Now change the destination address to router0's second port's
        // broadcast address.
        IPv4 origIpPkt = IPv4.class.cast(eth.getPayload());
        origIpPkt.setDestinationAddress(0x0a0001ff);
        // Still triggers ICMP if we don't supply the output port.
        Assert.assertTrue(rtr.canSendICMP(eth, null));
        // Doesn't trigger ICMP if we supply the output port.
        Assert.assertFalse(rtr.canSendICMP(eth, dstPortId));

        // Now change the destination address to a multicast address.
        origIpPkt.setDestinationAddress((225 << 24) + 0x000001ff);
        Assert.assertTrue(origIpPkt.isMcast());
        // Won't trigger ICMP regardless of the output port id.
        Assert.assertFalse(rtr.canSendICMP(eth, null));
        Assert.assertFalse(rtr.canSendICMP(eth, dstPortId));

        // Now change the network dst address back to normal and then change
        // the ethernet dst address to a multicast/broadcast.
        origIpPkt.setDestinationAddress(nwDst);
        Assert.assertTrue(rtr.canSendICMP(eth, dstPortId));
        // Use any address that has an odd number in first byte.
        MAC mcastMac = MAC.fromString("07:cd:cd:ab:ab:34");
        eth.setDestinationMACAddress(mcastMac);
        Assert.assertTrue(eth.isMcast());
        // Won't trigger ICMP regardless of the output port id.
        Assert.assertFalse(rtr.canSendICMP(eth, null));
        Assert.assertFalse(rtr.canSendICMP(eth, dstPortId));

        // Now change the ethernet dst address back to normal and then change
        // the ip packet's fragment offset.
        eth.setDestinationMACAddress(dlDst);
        Assert.assertTrue(rtr.canSendICMP(eth, dstPortId));
        origIpPkt.setFragmentOffset((short) 3);
        // Won't trigger ICMP regardless of the output port id.
        Assert.assertFalse(rtr.canSendICMP(eth, null));
        Assert.assertFalse(rtr.canSendICMP(eth, dstPortId));

        // Change the fragment offset back to zero. Then make and ICMP error in
        // response to the original UDP.
        origIpPkt.setFragmentOffset((short) 0);
        Assert.assertTrue(rtr.canSendICMP(eth, dstPortId));
        ICMP icmp = new ICMP();
        icmp.setUnreachable(ICMP.UNREACH_CODE.UNREACH_HOST, origIpPkt);
        // The icmp packet will be emitted from the lastIngress port:
        // router0's logical port to router2.
        IPv4 ip = new IPv4();
        ip.setSourceAddress(rtr0to2LogPortNwAddr);
        ip.setDestinationAddress(origIpPkt.getSourceAddress());
        ip.setProtocol(ICMP.PROTOCOL_NUMBER);
        ip.setPayload(icmp);
        eth = new Ethernet();
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ip);
        eth.setSourceMACAddress(MAC.fromString("02:a1:b2:c3:d4:e5"));
        eth.setDestinationMACAddress(MAC.fromString("02:a1:b2:c3:d4:e6"));
        // ICMP errors can't trigger ICMP errors.
        Assert.assertFalse(rtr.canSendICMP(eth, null));
        Assert.assertFalse(rtr.canSendICMP(eth, dstPortId));

        // Make an ICMP echo request. Verify it can trigger ICMP errors.
        icmp = new ICMP();
        short id = -12345;
        short seq = -20202;
        byte[] data = new byte[] { (byte) 0xaa, (byte) 0xbb, (byte) 0xcc,
                (byte) 0xdd, (byte) 0xee, (byte) 0xff };
        icmp.setEchoRequest(id, seq, data);
        ip = new IPv4();
        ip.setPayload(icmp);
        ip.setProtocol(ICMP.PROTOCOL_NUMBER);
        // The ping can come from anywhere if one of the next hops is a
        int senderIP = Net.convertStringAddressToInt("10.0.2.13");
        ip.setSourceAddress(senderIP);
        ip.setDestinationAddress(Net.convertStringAddressToInt("10.0.2.1"));
        eth = new Ethernet();
        eth.setPayload(ip);
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setDestinationMACAddress(MAC.fromString("02:cd:ef:01:23:45"));
        eth.setSourceMACAddress(MAC.fromString("02:cd:ef:01:23:46"));
        Assert.assertTrue(rtr.canSendICMP(eth, null));
        Assert.assertTrue(rtr.canSendICMP(eth, dstPortId));
    }

    @Test
    public void testPacketFromTunnel() throws StateAccessException {
        // Send a packet into the tunnel port corresponding to router2's
        // second port and destined for router2's first port.
        short inPortNum = 21;
        short outPortNum = 20;
        OFPhysicalPort phyPortOut = phyPorts.get(2).get(0);
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        Ethernet eth = TestRouter.makeUDP(
                new MAC(phyPortOut.getHardwareAddress()),
                MAC.fromString("02:00:11:44:88:ff"), 0x0a020133,
                0x0a020011, (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        // Set the GRE tunnel id to represent the destination port
        UUID egressUuid = portNumToUuid.get((short)20);
        ZkNodeEntry<UUID, PortConfig> tunnelIdEntry = portMgr.get(egressUuid);
        int tunnelId = tunnelIdEntry.value.greKey;
        vrnCtrl.onPacketIn(32331, data.length, inPortNum, data, tunnelId);
        // ARP was resolved by ingress controller. So there should be a flow
        // installed and a forwarded packet.
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(outPortNum, (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(32331, pkt.bufferId);

        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, inPortNum);
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(ofAction);
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                32331, false, actions);
    }

    public static void checkICMP(char type, char code, IPv4 triggerIPPkt,
            MAC dlSrc, MAC dlDst, int nwSrc, int nwDst, byte[] icmpData)
            throws MalformedPacketException {
        Ethernet eth = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(icmpData, 0, icmpData.length);
        eth.deserialize(bb);
        Assert.assertTrue(dlSrc.equals(eth.getSourceMACAddress()));
        Assert.assertTrue(dlDst.equals(eth.getDestinationMACAddress()));
        Assert.assertEquals(IPv4.ETHERTYPE, eth.getEtherType());
        IPv4 ip = IPv4.class.cast(eth.getPayload());
        Assert.assertEquals(nwSrc, ip.getSourceAddress());
        Assert.assertEquals(nwDst, ip.getDestinationAddress());
        Assert.assertEquals(ICMP.PROTOCOL_NUMBER, ip.getProtocol());
        ICMP icmp = ICMP.class.cast(ip.getPayload());
        Assert.assertEquals(type, icmp.getType());
        Assert.assertEquals(code, icmp.getCode());
        byte[] data = triggerIPPkt.serialize();
        int length = triggerIPPkt.getHeaderLength() * 4 + 8;
        if (length < data.length)
            data = Arrays.copyOf(data, length);
        Assert.assertTrue(Arrays.equals(data, icmp.getData()));
    }

    @Test
    public void testLocalPacketArpTimeout() throws Exception {
        // Send a packet to router1's first port to an address on router2's
        // first port. Note that we traverse 3 routers.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortIn = phyPorts.get(1).get(0);
        OFPhysicalPort phyPortOut = phyPorts.get(2).get(0);
        MAC mac = MAC.fromString("02:00:11:22:00:01");
        Ethernet eth = TestRouter.makeUDP(mac,
                new MAC(phyPortIn.getHardwareAddress()), 0x0a010005,
                0x0a020008, (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        vrnCtrl.onPacketIn(123456, data.length, phyPortIn.getPortNumber(),
                data);
        // Poke the reactor to drain the queued packet (ARP request).
        reactor.incrementTime(0, TimeUnit.SECONDS);
        // The router will have to ARP, so no flows installed yet, but one
        // unbuffered packet should have been emitted.
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(phyPortOut.getPortNumber(),
                (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        byte[] arpData = TestRouter.makeArpRequest(
                new MAC(phyPortOut.getHardwareAddress()), 0x0a020001,
                0x0a020008).serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Pre-seed the ARP cache with the original sender's mac so that the
        // ICMP doesn't require ARP. Avoid generating a retry by setting it
        // well in the future.
        long now = reactor.currentTimeMillis();
        arpTables.get(routerIds.get(1)).put(
                new IntIPv4(0x0a010005), new ArpCacheEntry(
                mac, now + Router.ARP_TIMEOUT_MILLIS,
                now + Router.ARP_TIMEOUT_MILLIS+1, now));

        // If we let 60 seconds go by the ARP will timeout, an ICMP !H will be
        // generated and a 'drop' flow installed.
        reactor.incrementTime(Router.ARP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        // Check the ICMP. It was generated by rtr2's logical port so its MAC
        // addresses should be rewritten by the other routers.
        pkt = controllerStub.sentPackets.get(1);
        Assert.assertEquals(3, pkt.actions.size());
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource)ofAction).setDataLayerAddress(
                phyPortIn.getHardwareAddress());
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination)ofAction).setDataLayerAddress(
                mac.getAddress());
        Assert.assertTrue(ofAction.equals(pkt.actions.get(1)));
        ofAction = new OFActionOutput(phyPortIn.getPortNumber(), (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(2)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertEquals(OFPort.OFPP_NONE.getValue(), pkt.inPort);
        // The network source address is that of the port on router2 that
        // generated the ICMP (the logical port): rtr2LogPortNwAddr.
        // The MACs of the ICMP (before the actions are applied) are the MACs
        // on the link between rtr2 and rtr0.
        checkICMP(ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_HOST.toChar(),
                IPv4.class.cast(eth.getPayload()), rtr2LogPortMAC,
                rtr0to2LogPortMAC, rtr2LogPortNwAddr, 0x0a010005, pkt.data);
        // Now check the Drop Flow.
        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, phyPortIn.getPortNumber());
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                VRNController.NO_IDLE_TIMEOUT,
                VRNController.TEMPORARY_DROP_SECONDS, 123456, false,
                new ArrayList<OFAction>());

    }

    private void addUplink() throws StateAccessException,
            ZkStateSerializationException {
        // Add an uplink to router0.
        uplinkId = ShortUUID.intTo32BitUUID(26473345);
        int p2pUplinkNwAddr = 0xc0a80004;
        uplinkGatewayAddr = p2pUplinkNwAddr + 1;
        uplinkPortAddr = p2pUplinkNwAddr + 2;
        short portNum = 897;

        MAC mac = MAC.fromString("02:33:55:77:99:11");
        //new byte[] { (byte) 0x02, (byte) 0xad,
        //(byte) 0xee, (byte) 0xda, (byte) 0xde, (byte) 0xed });
        PortConfig portConfig = new PortDirectory.MaterializedRouterPortConfig(
                routerIds.get(0), p2pUplinkNwAddr, 30, uplinkPortAddr, mac,
                null, 0xc0a80004, 30, null);
        uplinkId = portMgr.create(portConfig);
        Route rt = new Route(0, 0, 0, 0, NextHop.PORT, uplinkId,
                uplinkGatewayAddr, 1, null, routerIds.get(0));
        routeMgr.create(rt);
        ovsdb.setPortExternalId(datapathId, portNum, "midonet",
                                uplinkId.toString());
        uplinkPhyPort = new OFPhysicalPort();
        uplinkPhyPort.setPortNumber(portNum);
        uplinkPhyPort.setName("uplinkPort");
        uplinkPhyPort.setHardwareAddress(mac.getAddress());
        vrnCtrl.onPortStatus(uplinkPhyPort,
                OFPortStatus.OFPortReason.OFPPR_ADD);

        // TODO(pino, dan): fix this.
        // Two flows should have been installed for each locally added port.
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        // Check it's a rule:
        //       {match:[tunnelID=greKey], actions:[output(uplinkPort)]}
        List<OFAction> tunnelActions = new ArrayList<OFAction>();
        tunnelActions.add(new OFActionOutput(portNum, (short)0));
        MockControllerStub.Flow expectedFlow = new MockControllerStub.Flow(
                new MidoMatch(), 0, OFFlowMod.OFPFC_ADD, (short)0, (short)0,
                VRNController.FLOW_PRIORITY, -1, VRNController.nonePort, false,
                false, false, tunnelActions, portConfig.greKey);
        Assert.assertTrue(expectedFlow.equals(controllerStub.addedFlows.get(0))
                || expectedFlow.equals(controllerStub.addedFlows.get(1)));
        // Clear the flows: unit-tests assume the addedFlows queue starts empty.
        controllerStub.addedFlows.clear();
    }

    @Test
    public void testDnat() throws StateAccessException,
            ZkStateSerializationException, RuleIndexOutOfBoundsException,
            JsonParseException, KeeperException, InterruptedException,
            IOException {
        // First add the uplink to router0.
        addUplink();
        // Now add a dnat rule to map 0x808e0005:80 to 0x0a010009:10080, an
        // address on router1's first port.
        int natPublicNwAddr = 0x808e0005;
        short natPublicTpPort = 80;
        int natPrivateNwAddr = 0x0a010009;
        short natPrivateTpPort = 10080;
        UUID chainId = chainMgr.create(new ChainConfig("PREROUTING"));
        // Set this chain as the inboundFilter of a RouterConfig.
        RouterConfig rtrConfig = new RouterConfig(chainId, null);
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(natPrivateNwAddr, natPrivateNwAddr,
                natPrivateTpPort, natPrivateTpPort));
        Condition cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(uplinkId);
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwDstIp = natPublicNwAddr;
        cond.nwDstLength = 32;
        cond.tpDstStart = natPublicTpPort;
        cond.tpDstEnd = natPublicTpPort;
        Rule r = new ForwardNatRule(cond, Action.ACCEPT, chainId, 1,
                true /* dnat */, nats);
        ruleMgr.create(r);
        cond = new Condition();
        cond.outPortIds = new HashSet<UUID>();
        cond.outPortIds.add(uplinkId);
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwSrcIp = natPrivateNwAddr;
        cond.nwSrcLength = 32;
        cond.tpSrcStart = natPrivateTpPort;
        cond.tpSrcEnd = natPrivateTpPort;
        chainId = chainMgr.create(new ChainConfig("POSTROUTING"));
        // Set this as the outbound chain of the RouterConfig
        rtrConfig.outboundFilter = chainId;
        // Update router0 to use the RouterConfig
        routerMgr.update(routerIds.get(0), rtrConfig);
        // Poke the reactor so that Router0 will find its new config.
        reactor.incrementTime(0, TimeUnit.SECONDS);
        r = new ReverseNatRule(cond, Action.ACCEPT, chainId, 1,
                true /* dnat */);
        ruleMgr.create(r);
        TopologyChecker.checkRouter(routerIds.get(0), basePath, dir);

        // Now send a packet into the uplink directed to the natted addr/port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortOut = phyPorts.get(1).get(0);
        int extNwAddr = 0xd2000004; // addr of original sender.
        short extTpPort = 3427; // port of original sender.
        MAC extDlAddr = MAC.fromString("02:aa:bb:cc:dd:01");
        Ethernet eth = TestRouter.makeUDP(extDlAddr,
                new MAC(uplinkPhyPort.getHardwareAddress()), extNwAddr,
                natPublicNwAddr, extTpPort, natPublicTpPort, payload);
        byte[] data = eth.serialize();
        vrnCtrl.onPacketIn(12121, data.length,
                uplinkPhyPort.getPortNumber(), data);
        // Two nat mappings should be in the cache (fwd and rev).
        Assert.assertEquals(2, cache.map.size());
        // Poke the reactor to drain the queued packet (ARP request).
        reactor.incrementTime(0, TimeUnit.SECONDS);
        // The router will have to ARP, so no flows installed yet, but one
        // unbuffered packet should have been emitted.
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(phyPortOut.getPortNumber(),
                (short) 0);
        Assert.assertEquals(ofAction, pkt.actions.get(0));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        byte[] arpData = TestRouter.makeArpRequest(
                new MAC(phyPortOut.getHardwareAddress()), 0x0a010001,
                natPrivateNwAddr).serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. A flow is installed and a packet is
        // emitted from the switch.
        MAC mac = MAC.fromString("02:dd:33:33:dd:01");
        arpData = TestRouter.makeArpReply(mac,
                new MAC(phyPortOut.getHardwareAddress()), natPrivateNwAddr,
                0x0a010001).serialize();
        vrnCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, arpData.length,
                phyPortOut.getPortNumber(), arpData);
        // Poke the reactor so that the paused packet can be processed.
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, uplinkPhyPort.getPortNumber());
        MidoMatch fwdMatch = match; // use this later to call onFlowRemoved()
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(
                phyPortOut.getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(
                mac.getAddress());
        actions.add(ofAction);
        ofAction = new OFActionNetworkLayerDestination();
        ((OFActionNetworkLayerAddress) ofAction).setNetworkAddress(
                natPrivateNwAddr);
        actions.add(ofAction);
        ofAction = new OFActionTransportLayerDestination();
        ((OFActionTransportLayer) ofAction).setTransportPort(natPrivateTpPort);
        actions.add(ofAction);
        actions.add(tmp); // the Output action goes at the end.
        // Note that the installed flow subscribes to the removal notification.
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                12121, true, actions);
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(1);
        Assert.assertEquals(actions, pkt.actions);
        Assert.assertEquals(12121, pkt.bufferId);

        // Now create a reply packet from the natted private addr/port.
        eth = TestRouter.makeUDP(mac, new MAC(phyPortOut.getHardwareAddress()),
                natPrivateNwAddr, extNwAddr, natPrivateTpPort, extTpPort,
                payload);
        data = eth.serialize();
        vrnCtrl.onPacketIn(13131, data.length, phyPortOut.getPortNumber(),
                data);
        // Two nat mappings should still be in the cache (fwd and rev).
        Assert.assertEquals(2, cache.map.size());
        // The router will have to ARP, so no additional flows installed yet,
        // but another unbuffered packet should have been emitted.
        // Poke the reactor to drain the queued packet (ARP request).
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(3, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        pkt = controllerStub.sentPackets.get(2);
        ofAction = new OFActionOutput(uplinkPhyPort.getPortNumber(), (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        arpData = TestRouter.makeArpRequest(
                new MAC(uplinkPhyPort.getHardwareAddress()), uplinkPortAddr,
                uplinkGatewayAddr).serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. A flow is installed and a packet is
        // emitted from the switch.
        MAC uplinkGatewayMac = MAC.fromString("02:dd:55:66:dd:01");
        arpData = TestRouter.makeArpReply(uplinkGatewayMac,
                new MAC(uplinkPhyPort.getHardwareAddress()), uplinkGatewayAddr,
                uplinkPortAddr).serialize();
        vrnCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, arpData.length,
                uplinkPhyPort.getPortNumber(), arpData);
        // Poke the reactor so that the paused packet can be processed.
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        // The return packet's ingress is router1's first port.
        match = AbstractController.createMatchFromPacket(
                eth, phyPortOut.getPortNumber());
        actions.clear();
        tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(
                uplinkPhyPort.getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(
                uplinkGatewayMac.getAddress());
        actions.add(ofAction);
        ofAction = new OFActionNetworkLayerSource();
        ((OFActionNetworkLayerAddress) ofAction).setNetworkAddress(
                natPublicNwAddr);
        actions.add(ofAction);
        ofAction = new OFActionTransportLayerSource();
        ((OFActionTransportLayer) ofAction).setTransportPort(natPublicTpPort);
        actions.add(ofAction);
        actions.add(tmp); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(1), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                13131, false, actions);
        Assert.assertEquals(4, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(3);
        Assert.assertEquals(actions, pkt.actions);
        Assert.assertEquals(13131, pkt.bufferId);

        // Two nat mappings should still be in the cache (fwd and rev).
        Assert.assertEquals(2, cache.map.size());

        // Now the Dnat's mappings should keep getting refreshed in the cache
        // until the controller gets a flowRemoved callback for the orginal
        // forward flow match.
        Set<String> natKeys = new HashSet<String>();
        natKeys.add(NatLeaseManager.makeCacheKey(routerIds.get(0).toString() +
                NatLeaseManager.FWD_DNAT_PREFIX, extNwAddr, extTpPort,
                natPublicNwAddr, natPublicTpPort));
        natKeys.add(NatLeaseManager.makeCacheKey(routerIds.get(0).toString() +
                NatLeaseManager.REV_DNAT_PREFIX, extNwAddr, extTpPort,
                natPrivateNwAddr, natPrivateTpPort));
        checkNatRefresh(natKeys, fwdMatch);
    }

    private void checkNatRefresh(Collection<String> keys, OFMatch fwdMatch) {
        int refresh = cacheExpireSecs / 2;
        Long expectExpire = reactor.currentTimeMillis() + cacheExpireSecs
                * 1000;
        for (String key : keys)
            Assert.assertEquals(expectExpire, cache.getExpireTimeMillis(key));
        // Do the following in a loop to make sure it's working correctly.
        for (int i = 0; i < 10; i++) {
            // Now advance time by one second less than REFRESH_SECONDS
            reactor.incrementTime(refresh - 1, TimeUnit.SECONDS);
            // The expiration times in the cache have not changed.
            for (String key : keys)
                Assert.assertEquals(expectExpire,
                        cache.getExpireTimeMillis(key));
            // Now advance the time by one second.
            reactor.incrementTime(1, TimeUnit.SECONDS);
            expectExpire = reactor.currentTimeMillis() + cacheExpireSecs * 1000;
            for (String key : keys)
                Assert.assertEquals(expectExpire,
                        cache.getExpireTimeMillis(key));
        }
        // Now pretend the forward flow idled out.
        vrnCtrl.onFlowRemoved(fwdMatch, (long) 0, (short) 0,
                OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT, 1000, 0, (short) 30,
                (long) 2271, (long) 122345);
        // Now advance time by one second MORE than REFRESH_SECONDS
        reactor.incrementTime(refresh + 1, TimeUnit.SECONDS);
        // The expiration times in the cache are the same. Refresh stopped.
        for (String key : keys)
            Assert.assertEquals(expectExpire, cache.getExpireTimeMillis(key));
    }

    @Test
    public void testFloatingIp() throws StateAccessException,
            ZkStateSerializationException, RuleIndexOutOfBoundsException {
        // First add the uplink to router0.
        addUplink();
        // Add 2 rules:
        // 1) forward snat rule 0x0a010009 to floating ip 0x808e0005
        // 2) forward dnat rule 0x808e0005 to internal 0x0a010009
        UUID chainId = chainMgr.create(new ChainConfig("POSTROUTING"));
        // Set this chain as the outbound chain of a RouterConfig
        RouterConfig rtrConfig = new RouterConfig(null, chainId);
        int floatingIp = 0x808e0005;
        int internalIp = 0x0a010009;
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(floatingIp, floatingIp, (short) 0, (short) 0));
        Condition cond = new Condition();
        cond.outPortIds = new HashSet<UUID>();
        cond.outPortIds.add(uplinkId);
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwSrcIp = internalIp;
        cond.nwSrcLength = 32;
        Rule r = new ForwardNatRule(cond, Action.ACCEPT, chainId, 1,
                false /* snat */, nats);
        ruleMgr.create(r);
        // dnat rule:
        nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(internalIp, internalIp, (short) 0, (short) 0));
        chainId = chainMgr.create(new ChainConfig("PREROUTING"));
        // Set this as the inbound chain of the RouterConfig
        rtrConfig.inboundFilter = chainId;
        // Update router0 to use the RouterConfig
        routerMgr.update(routerIds.get(0), rtrConfig);
        // Poke the reactor so that Router0 will find its new config.
        reactor.incrementTime(0, TimeUnit.SECONDS);
        cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(uplinkId);
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwDstIp = floatingIp;
        cond.nwDstLength = 32;
        r = new ForwardNatRule(cond, Action.ACCEPT, chainId, 1,
                true /* dnat */, nats);
        ruleMgr.create(r);

        // Now send a packet into the uplink directed to the floating IP.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortOut = phyPorts.get(1).get(0);
        int extNwAddr = 0xd2000004; // addr of original sender.
        short extTpPort = 3427; // port of original sender.
        short internalTpPort = 8642; // floatingIp's port, won't change.
        MAC extDlAddr = MAC.fromString("02:aa:bb:cc:dd:01");
        Ethernet eth = TestRouter.makeUDP(extDlAddr,
                new MAC(uplinkPhyPort.getHardwareAddress()), extNwAddr,
                floatingIp, extTpPort, internalTpPort, payload);
        byte[] data = eth.serialize();
        vrnCtrl.onPacketIn(12121, data.length,
                uplinkPhyPort.getPortNumber(), data);
        // Poke the reactor to drain the queued packet (ARP request).
        reactor.incrementTime(0, TimeUnit.SECONDS);
        // No nat mappings have been added to the cache
        Assert.assertEquals(0, cache.map.size());
        // The router will have to ARP, so no flows installed yet, but one
        // unbuffered packet should have been emitted.
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(phyPortOut.getPortNumber(),
                (short) 0);
        Assert.assertEquals(ofAction, pkt.actions.get(0));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        byte[] arpData = TestRouter.makeArpRequest(
                new MAC(phyPortOut.getHardwareAddress()), 0x0a010001,
                internalIp).serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. A flow is installed and a packet is
        // emitted from the switch.
        MAC mac = MAC.fromString("02:dd:33:33:dd:01");
        arpData = TestRouter.makeArpReply(mac,
                new MAC(phyPortOut.getHardwareAddress()), internalIp,
                0x0a010001).serialize();
        vrnCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, arpData.length,
                phyPortOut.getPortNumber(), arpData);
        // Poke the reactor to drain the previously paused packet.
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, uplinkPhyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(phyPortOut
                .getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(
                mac.getAddress());
        actions.add(ofAction);
        ofAction = new OFActionNetworkLayerDestination();
        ((OFActionNetworkLayerAddress) ofAction).setNetworkAddress(internalIp);
        actions.add(ofAction);
        actions.add(tmp); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                12121, false, actions);
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(1);
        Assert.assertEquals(actions, pkt.actions);
        Assert.assertEquals(12121, pkt.bufferId);

        // Now create a reply packet from the natted private addr/port.
        eth = TestRouter.makeUDP(mac, new MAC(phyPortOut.getHardwareAddress()),
                internalIp, extNwAddr, internalTpPort, extTpPort, payload);
        data = eth.serialize();
        vrnCtrl.onPacketIn(13131, data.length, phyPortOut.getPortNumber(),
                data);
        // No nat mappings have been added to the cache
        Assert.assertEquals(0, cache.map.size());
        // The router will have to ARP, so no additional flows installed yet,
        // but another unbuffered packet should have been emitted.
        // Poke the reactor to drain the queued packet (ARP request).
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(3, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        pkt = controllerStub.sentPackets.get(2);
        ofAction = new OFActionOutput(uplinkPhyPort.getPortNumber(), (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        arpData = TestRouter.makeArpRequest(
                new MAC(uplinkPhyPort.getHardwareAddress()), uplinkPortAddr,
                uplinkGatewayAddr).serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. A flow is installed and a packet is
        // emitted from the switch.
        MAC uplinkGatewayMac = MAC.fromString("02:dd:55:66:dd:01");
        arpData = TestRouter.makeArpReply(uplinkGatewayMac,
                new MAC(uplinkPhyPort.getHardwareAddress()), uplinkGatewayAddr,
                uplinkPortAddr).serialize();
        vrnCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, arpData.length,
                uplinkPhyPort.getPortNumber(), arpData);
        // Poke the reactor to drain the previously paused packet.
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        // The return packet's ingress is router1's first port.
        match = AbstractController.createMatchFromPacket(
                eth, phyPortOut.getPortNumber());
        actions.clear();
        tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(
                uplinkPhyPort.getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction)
                .setDataLayerAddress(uplinkGatewayMac.getAddress());
        actions.add(ofAction);
        ofAction = new OFActionNetworkLayerSource();
        ((OFActionNetworkLayerAddress) ofAction).setNetworkAddress(floatingIp);
        actions.add(ofAction);
        actions.add(tmp); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(1), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                13131, false, actions);
        Assert.assertEquals(4, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(3);
        Assert.assertEquals(actions, pkt.actions);
        Assert.assertEquals(13131, pkt.bufferId);

        // Now create another packet from the internal IP (and port) to a
        // different destination. This shows that conversations can be initiated
        // from inside or outside the network.
        extNwAddr = extNwAddr + 1;
        extTpPort = (short) (extTpPort + 1);
        internalTpPort = (short) (internalTpPort + 1);
        eth = TestRouter.makeUDP(mac, new MAC(phyPortOut.getHardwareAddress()),
                internalIp, extNwAddr, internalTpPort, extTpPort, payload);
        data = eth.serialize();
        vrnCtrl.onPacketIn(222, data.length, phyPortOut.getPortNumber(),
                data);
        // No nat mappings have been added to the cache
        Assert.assertEquals(0, cache.map.size());
        // We already know the external gateway's mac, so no ARP needed. A new
        // flow is installed and this packet is emitted from the uplink.
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(3, controllerStub.addedFlows.size());
        // The return packet's ingress is router1's first port.
        match = AbstractController.createMatchFromPacket(
                eth, phyPortOut.getPortNumber());
        actions.clear();
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(
                uplinkPhyPort.getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(
                uplinkGatewayMac.getAddress());
        actions.add(ofAction);
        ofAction = new OFActionNetworkLayerSource();
        ((OFActionNetworkLayerAddress) ofAction).setNetworkAddress(floatingIp);
        actions.add(ofAction);
        ofAction = new OFActionOutput(uplinkPhyPort.getPortNumber(), (short) 0);
        actions.add(ofAction);
        checkInstalledFlow(controllerStub.addedFlows.get(2), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT, 222,
                false, actions);
        Assert.assertEquals(5, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(4);
        Assert.assertEquals(actions, pkt.actions);
        Assert.assertEquals(222, pkt.bufferId);
    }

    @Test
    public void testSnat() throws StateAccessException,
            ZkStateSerializationException, RuleIndexOutOfBoundsException {
        // First add the uplink to router0.
        addUplink();
        // Now add a snat rule to map source addresses on router2
        // (0x0a020000/16) to public address 0x808e0005 for any packet that
        // is going outside 0x0a000000/8.
        UUID chainId = chainMgr.create(new ChainConfig("POSTROUTING"));
        // Set this chain as the outboundFilter of a RouterConfig
        RouterConfig rtrConfig = new RouterConfig(null, chainId);
        int natPublicNwAddr = 0x808e0005;
        int natPrivateNwAddr = 0x0a020000;
        Set<NatTarget> nats = new HashSet<NatTarget>();
        NatTarget nat = new NatTarget(natPublicNwAddr, natPublicNwAddr,
                (short) 49152, (short) 65535);
        nats.add(nat);
        Condition cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(portOn0to2);
        cond.outPortIds = new HashSet<UUID>();
        cond.outPortIds.add(uplinkId);
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwSrcIp = natPrivateNwAddr;
        cond.nwDstLength = 16;
        cond.nwDstIp = 0x0a000000;
        cond.nwDstLength = 8;
        cond.nwDstInv = true;
        Rule r = new ForwardNatRule(cond, Action.ACCEPT, chainId, 1,
                false /* snat */, nats);
        ruleMgr.create(r);
        // Make another post-routing rule that drops packets that ingress the
        // uplink and would also egress the uplink.
        cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(uplinkId);
        cond.outPortIds = new HashSet<UUID>();
        cond.outPortIds.add(uplinkId);
        r = new LiteralRule(cond, Action.DROP, chainId, 2);
        ruleMgr.create(r);

        chainId = chainMgr.create(new ChainConfig("PREROUTING"));
        // Set this as the inbound chain of the RouterConfig
        rtrConfig.inboundFilter = chainId;
        // Update router0 to use the RouterConfig
        routerMgr.update(routerIds.get(0), rtrConfig);
        // Poke the reactor so that Router0 will find its new config.
        reactor.incrementTime(0, TimeUnit.SECONDS);
        cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(uplinkId);
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwSrcIp = 0x0a000000;
        cond.nwSrcLength = 16;
        cond.nwSrcInv = true;
        cond.nwDstIp = natPublicNwAddr;
        cond.nwDstLength = 32;
        r = new ReverseNatRule(cond, Action.ACCEPT, chainId, 1,
                               false /* snat */);
        ruleMgr.create(r);

        // Send a packet into the uplink directed to the natted addr/port.
        // This packet will be dropped since it won't find any reverse snat
        // mapping.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortRtr2 = phyPorts.get(2).get(0); // 0x0a020000/24
        int extNwAddr = 0xd2000004; // addr of a host outside the network.
        short extTpPort = 3427; // port of host outside the network.
        MAC uplinkGatewayMac = MAC.fromString("02:dd:55:66:dd:01");
        Ethernet eth = TestRouter.makeUDP(uplinkGatewayMac, new MAC(
                uplinkPhyPort.getHardwareAddress()), extNwAddr,
                natPublicNwAddr, extTpPort, (short) 45000, payload);
        byte[] data = eth.serialize();
        vrnCtrl.onPacketIn(12121, data.length,
                uplinkPhyPort.getPortNumber(), data);
        // No nat mappings have been added to the cache
        Assert.assertEquals(0, cache.map.size());
        // Look for the drop flow.
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = AbstractController.createMatchFromPacket(
                eth, uplinkPhyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                VRNController.NO_IDLE_TIMEOUT,
                VRNController.TEMPORARY_DROP_SECONDS, 12121, false, actions);

        // Send a packet into router2's port directed to the external addr/port.
        MAC localMac = MAC.fromString("02:89:67:45:23:01");
        int localNwAddr = 0x0a020008;
        short localTpPort = (short) 47000;
        eth = TestRouter.makeUDP(localMac,
                new MAC(phyPortRtr2.getHardwareAddress()), localNwAddr,
                extNwAddr, localTpPort, extTpPort, payload);
        data = eth.serialize();
        vrnCtrl.onPacketIn(13131, data.length, phyPortRtr2.getPortNumber(),
                data);
        // Two nat mappings should be in the cache (fwd and rev).
        Assert.assertEquals(2, cache.map.size());
        // The router will have to ARP, so no new flows installed yet, but one
        // unbuffered packet should have been emitted.
        // Poke the reactor to drain the queued packet (ARP request).
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(uplinkPhyPort.getPortNumber(),
                (short) 0);
        Assert.assertEquals(ofAction, pkt.actions.get(0));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        byte[] arpData = TestRouter.makeArpRequest(
                new MAC(uplinkPhyPort.getHardwareAddress()), uplinkPortAddr,
                uplinkGatewayAddr).serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. A flow is installed and a packet is
        // emitted from the switch.
        arpData = TestRouter.makeArpReply(uplinkGatewayMac,
                new MAC(uplinkPhyPort.getHardwareAddress()), uplinkGatewayAddr,
                uplinkPortAddr).serialize();
        vrnCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, arpData.length,
                uplinkPhyPort.getPortNumber(), arpData);
        // Poke the reactor to drain the previously paused packet.
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        match = AbstractController.createMatchFromPacket(
                eth, phyPortRtr2.getPortNumber());
        MidoMatch fwdMatch = match; // Use this later for onFlowRemoved()
        actions.clear();
        OFAction tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(
                uplinkPhyPort.getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(
                uplinkGatewayMac.getAddress());
        actions.add(ofAction);
        ofAction = new OFActionNetworkLayerSource();
        ((OFActionNetworkLayerAddress) ofAction).setNetworkAddress(
                natPublicNwAddr);
        actions.add(ofAction);
        MockControllerStub.Flow flow = controllerStub.addedFlows.get(1);
        Assert.assertEquals(5, flow.actions.size());
        OFActionTransportLayerSource tpSrcAction =
                OFActionTransportLayerSource.class.cast(flow.actions.get(3));
        short natPublicTpPort = tpSrcAction.getTransportPort();
        Assert.assertTrue(nat.tpStart <= natPublicTpPort);
        Assert.assertTrue(natPublicTpPort <= nat.tpEnd);
        // Add this into the list of expected actions.
        actions.add(tpSrcAction);
        actions.add(tmp); // the Output action goes at the end.
        // Note that this installed flow subscribes for removal notifications.
        checkInstalledFlow(flow, match, idleFlowTimeoutSeconds,
                VRNController.NO_HARD_TIMEOUT, 13131, true, actions);
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(1);
        Assert.assertEquals(actions, pkt.actions);
        Assert.assertEquals(13131, pkt.bufferId);

        // Now create a reply packet from the external addr/port.
        eth = TestRouter.makeUDP(uplinkGatewayMac,
                new MAC(uplinkPhyPort.getHardwareAddress()), extNwAddr,
                natPublicNwAddr, extTpPort, natPublicTpPort, payload);
        data = eth.serialize();
        vrnCtrl.onPacketIn(14141, data.length,
                uplinkPhyPort.getPortNumber(), data);
        // Two nat mappings should still be in the cache (fwd and rev).
        Assert.assertEquals(2, cache.map.size());
        // The router will have to ARP, so no additional flows installed yet,
        // but another unbuffered packet should have been emitted.
        // Poke the reactor to drain the queued packet (ARP request).
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(3, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        pkt = controllerStub.sentPackets.get(2);
        Assert.assertEquals(1, pkt.actions.size());
        ofAction = new OFActionOutput(phyPortRtr2.getPortNumber(), (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        arpData = TestRouter.makeArpRequest(
                new MAC(phyPortRtr2.getHardwareAddress()), 0x0a020001,
                localNwAddr).serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. A flow is installed and a packet is
        // emitted from the switch.
        arpData = TestRouter.makeArpReply(localMac,
                new MAC(phyPortRtr2.getHardwareAddress()), localNwAddr,
                0x0a020001).serialize();
        vrnCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, arpData.length,
                phyPortRtr2.getPortNumber(), arpData);
        // Poke the reactor to drain the previously paused packet.
        reactor.incrementTime(0, TimeUnit.SECONDS);
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(3, controllerStub.addedFlows.size());
        match = AbstractController.createMatchFromPacket(
                eth, uplinkPhyPort.getPortNumber());
        actions.clear();
        tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(
                phyPortRtr2.getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(
                localMac.getAddress());
        actions.add(ofAction);
        ofAction = new OFActionNetworkLayerDestination();
        ((OFActionNetworkLayerAddress) ofAction).setNetworkAddress(localNwAddr);
        actions.add(ofAction);
        ofAction = new OFActionTransportLayerDestination();
        ((OFActionTransportLayer) ofAction).setTransportPort(localTpPort);
        actions.add(ofAction);
        actions.add(tmp); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(2), match,
                idleFlowTimeoutSeconds, VRNController.NO_HARD_TIMEOUT,
                14141, false, actions);
        Assert.assertEquals(4, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(3);
        Assert.assertEquals(actions, pkt.actions);
        Assert.assertEquals(14141, pkt.bufferId);

        Set<String> natKeys = new HashSet<String>();
        natKeys.add(NatLeaseManager.makeCacheKey(routerIds.get(0).toString()
                + NatLeaseManager.FWD_SNAT_PREFIX, localNwAddr, localTpPort,
                extNwAddr, extTpPort));
        natKeys.add(NatLeaseManager.makeCacheKey(routerIds.get(0).toString()
                + NatLeaseManager.REV_SNAT_PREFIX, natPublicNwAddr,
                natPublicTpPort, extNwAddr, extTpPort));
        checkNatRefresh(natKeys, fwdMatch);
    }

    @Test
    public void testBgpDataPath() throws StateAccessException,
            ZkStateSerializationException, UnknownHostException {
        // No flows should be installed at the beginning.
        Assert.assertEquals(0, controllerStub.addedFlows.size());

        // Create BGP config to the local port0 on router0.
        int routerId = 0;
        short remotePortNum = 0;
        UUID portId = portNumToUuid.get(new Short(remotePortNum));
        String remoteAddrString = "192.168.10.1";
        bgpMgr.create(new BgpConfig(portId, 65104,
                InetAddress.getByName(remoteAddrString), 12345));

        // Add the port to the datapath to invoke adding a service port for BGP.
        OFPhysicalPort remotePort = phyPorts.get(routerId).get(remotePortNum);
        vrnCtrl.onPortStatus(remotePort,
                OFPortStatus.OFPortReason.OFPPR_DELETE);
        vrnCtrl.onPortStatus(remotePort,
                OFPortStatus.OFPortReason.OFPPR_ADD);

        // Add the BGP service port.
        OFPhysicalPort servicePort = new OFPhysicalPort();
        // Offset local port number to avoid conflicts.
        short localPortNum = MockPortService.BGP_TCP_PORT;
        servicePort.setPortNumber(localPortNum);
        servicePort.setName("midobgp0");
        servicePort.setHardwareAddress(new byte[] { (byte) 0x02, (byte) 0xee,
                (byte) 0xdd, (byte) 0xcc, (byte) 0xff, (byte) localPortNum });
        // Set the external id so that AbstractController recognizes this
        // as a service port. However, it's is not used by the MockPortService.
        ovsdb.setPortExternalId(datapathId, localPortNum, "midolman_port_id",
                portId.toString());

        vrnCtrl.onPortStatus(servicePort,
                OFPortStatus.OFPortReason.OFPPR_ADD);

        // 10 flows (4 BGP, 2 ICMP, 2 ARP, 1 DHCP, 1 tunnel) are installed.
        // The DHCP flow is not specific to the BGP port setup. All locally
        // added ports get a pre-installed flow.
        Assert.assertEquals(10, controllerStub.addedFlows.size());
        // TODO(pino, dan): fix this. For now remove the DHCP and tunnel flows
        // because the rest of the test is oblivious to them.
        controllerStub.addedFlows.remove(1);
        controllerStub.addedFlows.remove(0);

        int localAddr = PortDirectory.MaterializedRouterPortConfig.class
                .cast(portMgr.get(portId).value).portAddr;
        int remoteAddr = Net.convertStringAddressToInt(remoteAddrString);
        MidoMatch match;
        List<OFAction> actions;

        // Check BGP flows from local to remote with remote TCP port specified.
        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(localAddr);
        match.setNetworkDestination(remoteAddr);
        match.setTransportDestination(MockPortService.BGP_TCP_PORT);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput((short) remotePortNum, (short) 0));
        checkInstalledFlow(controllerStub.addedFlows.get(0), match, (short) 0,
                (short) 0, ControllerStub.UNBUFFERED_ID, false, actions);

        // Check BGP flows from local to remote with local TCP port specified.
        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(localAddr);
        match.setNetworkDestination(remoteAddr);
        match.setTransportSource(MockPortService.BGP_TCP_PORT);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput((short) remotePortNum, (short) 0));
        checkInstalledFlow(controllerStub.addedFlows.get(1), match, (short) 0,
                (short) 0, ControllerStub.UNBUFFERED_ID, false, actions);

        // Check BGP flows from remote to local with local TCP port specified.
        match = new MidoMatch();
        match.setInputPort((short) remotePortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(remoteAddr);
        match.setNetworkDestination(localAddr);
        match.setTransportDestination(MockPortService.BGP_TCP_PORT);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        checkInstalledFlow(controllerStub.addedFlows.get(2), match, (short) 0,
                (short) 0, ControllerStub.UNBUFFERED_ID, false, actions);

        // Check BGP flows from remote to local with remote TCP port specified.
        match = new MidoMatch();
        match.setInputPort((short) remotePortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(remoteAddr);
        match.setNetworkDestination(localAddr);
        match.setTransportSource(MockPortService.BGP_TCP_PORT);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        checkInstalledFlow(controllerStub.addedFlows.get(3), match, (short) 0,
                (short) 0, ControllerStub.UNBUFFERED_ID, false, actions);

        // Check ARP request to the peer.
        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(ARP.ETHERTYPE);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput((short) remotePortNum, (short) 0));
        checkInstalledFlow(controllerStub.addedFlows.get(4), match, (short) 0,
                (short) 0, ControllerStub.UNBUFFERED_ID, false, actions);

        // Check ARP request from the peer.
        // One flow goes to the local, and the other goes to the controller.
        match = new MidoMatch();
        match.setInputPort((short) remotePortNum);
        match.setDataLayerType(ARP.ETHERTYPE);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput((short) localPortNum, (short) 0));
        actions.add(new OFActionOutput(OFPort.OFPP_CONTROLLER.getValue(),
                (short) 128));
        checkInstalledFlow(controllerStub.addedFlows.get(5), match, (short) 0,
                (short) 0, ControllerStub.UNBUFFERED_ID, false, actions);

        // Check ICMP flows from local with local address specified.
        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        match.setNetworkSource(localAddr);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput((short) remotePortNum, (short) 0));
        checkInstalledFlow(controllerStub.addedFlows.get(6), match, (short) 0,
                (short) 0, ControllerStub.UNBUFFERED_ID, false, actions);

        // Check ICMP flows to local with local address specified.
        match = new MidoMatch();
        match.setInputPort((short) remotePortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        match.setNetworkDestination(localAddr);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput((short) localPortNum, (short) 0));
        checkInstalledFlow(controllerStub.addedFlows.get(7), match, (short) 0,
                (short) 0, ControllerStub.UNBUFFERED_ID, false, actions);
    }

    @Test
    public void testOnPacketInMalformedDhcpPacket() {
        // Verify that a bad packet does not crash Midolman.
        // This packet contains a junk DHCP data.
        byte[] dhcpData = Arrays.copyOf(TestDHCP.dhcpBytes, 100);

        Ethernet eth = TestRouter.makeUDP(MAC.fromString("02:00:11:22:00:01"),
                MAC.fromString("02:00:11:22:00:01"), 0x0a000005, 0x0a040005,
                (short) 68, (short) 67, dhcpData);
        byte[] data = eth.serialize();
        OFPhysicalPort phyPort = phyPorts.get(0).get(0);

        vrnCtrl.onPacketIn(55, data.length, phyPort.getPortNumber(), data);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        // TODO: loadFromPacket() sometimes throws, so we don't use it.
        //       Should we attempt it in a try-block and use the resulting
        //       match if there was no exception?
        /*
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch m = new MidoMatch();
        m.loadFromPacket(data, (short)0);
        checkInstalledFlow(controllerStub.addedFlows.get(0), m,
                (short)0, vrnCtrl.TEMPORARY_DROP_SECONDS, 55, false,
                new ArrayList<OFAction>());
        */
    }
}
