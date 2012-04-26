// Copyright 2012 Midokura Inc.

package com.midokura.midolman;

import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.hamcrest.number.OrderingComparison.greaterThanOrEqualTo;
import static org.hamcrest.number.OrderingComparison.lessThanOrEqualTo;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.openflow.nxm.NxActionSetTunnelKey32;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MacPortMap;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortSetMap;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.util.Net;


public class TestBridge {
    Logger log = LoggerFactory.getLogger(TestBridge.class);

    private VRNController controller;
    private Bridge bridge;

    private Directory portLocDir, macPortDir;
    private PortToIntNwAddrMap portLocMap;
    private MacPortMap macPortMap;
    private MockOpenvSwitchDatabaseConnection ovsdb;
    private IntIPv4 publicIp;
    int dp_id = 43;
    MockControllerStub controllerStub;
    UUID portUuids[];
    MockReactor reactor;
    final int timeout_ms = 40*1000;
    int numPreinstalledFlows;
    final int normalIdle = VRNController.NORMAL_IDLE_TIMEOUT;
    final int normalPriority = VRNController.FLOW_PRIORITY;
    final int tempDropTime = VRNController.TEMPORARY_DROP_SECONDS;

    public static final OFAction OUTPUT_ALL_ACTION =
                new OFActionOutput(OFPort.OFPP_ALL.getValue(), (short)0);
    public static final OFAction OUTPUT_FLOOD_ACTION =
                new OFActionOutput(OFPort.OFPP_FLOOD.getValue(), (short)0);
    OFAction[] floodActionNoPort0;
    OFAction[] floodActionLocalOnly;
    int tunnelID;
    int[] portKeys = new int[8];

    // MACs:  8 normal addresses, and one multicast.
    MAC macList[] = { MAC.fromString("00:22:33:EE:EE:00"),
                      MAC.fromString("00:22:33:EE:EE:01"),
                      MAC.fromString("00:22:33:EE:EE:02"),
                      MAC.fromString("00:22:33:EE:EE:03"),
                      MAC.fromString("00:22:33:EE:EE:04"),
                      MAC.fromString("00:22:33:EE:EE:05"),
                      MAC.fromString("00:22:33:EE:EE:06"),
                      MAC.fromString("00:22:33:EE:EE:07"),
                      MAC.fromString("01:EE:EE:EE:EE:EE") };

    // Packets
    final Ethernet packet01 = makePacket(macList[0], macList[1]);
    final Ethernet packet03 = makePacket(macList[0], macList[3]);
    final Ethernet packet04 = makePacket(macList[0], macList[4]);
    final Ethernet packet0MC = makePacket(macList[0], macList[8]);
    final Ethernet packet10 = makePacket(macList[1], macList[0]);
    final Ethernet packet13 = makePacket(macList[1], macList[3]);
    final Ethernet packet15 = makePacket(macList[1], macList[5]);
    final Ethernet packet20 = makePacket(macList[2], macList[0]);
    final Ethernet packet23 = makePacket(macList[2], macList[3]);
    final Ethernet packet25 = makePacket(macList[2], macList[5]);
    final Ethernet packet26 = makePacket(macList[2], macList[6]);
    final Ethernet packet27 = makePacket(macList[2], macList[7]);
    final Ethernet packet41 = makePacket(macList[4], macList[1]);
    final Ethernet packet70 = makePacket(macList[7], macList[0]);
    final Ethernet packetMC0 = makePacket(macList[8], macList[0]);

    // Flow matches
    final MidoMatch flowmatch01 = makeFlowMatch(macList[0], macList[1]);
    final MidoMatch flowmatch03 = makeFlowMatch(macList[0], macList[3]);
    final MidoMatch flowmatch04 = makeFlowMatch(macList[0], macList[4]);
    final MidoMatch flowmatch0MC = makeFlowMatch(macList[0], macList[8]);
    final MidoMatch flowmatch10 = makeFlowMatch(macList[1], macList[0]);
    final MidoMatch flowmatch13 = makeFlowMatch(macList[1], macList[3]);
    final MidoMatch flowmatch15 = makeFlowMatch(macList[1], macList[5]);
    final MidoMatch flowmatch20 = makeFlowMatch(macList[2], macList[0]);
    final MidoMatch flowmatch23 = makeFlowMatch(macList[2], macList[3]);
    final MidoMatch flowmatch25 = makeFlowMatch(macList[2], macList[5]);
    final MidoMatch flowmatch26 = makeFlowMatch(macList[2], macList[6]);
    final MidoMatch flowmatch27 = makeFlowMatch(macList[2], macList[7]);
    final MidoMatch flowmatch41 = makeFlowMatch(macList[4], macList[1]);
    final MidoMatch flowmatch70 = makeFlowMatch(macList[7], macList[0]);
    final MidoMatch flowmatchMC0 = makeFlowMatch(macList[8], macList[0]);


    OFPhysicalPort[] phyPorts = {
        new OFPhysicalPort(), new OFPhysicalPort(), new OFPhysicalPort(),
        new OFPhysicalPort(), new OFPhysicalPort(), new OFPhysicalPort(),
        new OFPhysicalPort(), new OFPhysicalPort() };

    String[] peerStrList = { "192.168.1.50",    // local
                             "192.168.1.50",    // local
                             "192.168.1.50",    // local
                             "192.168.1.53",
                             "192.168.1.54",
                             "192.168.1.55",
                             "192.168.1.56",
                             "192.168.1.57" };

    static Ethernet makePacket(MAC srcMac, MAC dstMac) {
        ICMP icmpPacket = new ICMP();
        icmpPacket.setEchoRequest((short)0, (short)0,
                                  "echoechoecho".getBytes());
        IPv4 ipPacket = new IPv4();
        ipPacket.setPayload(icmpPacket);
        ipPacket.setProtocol(ICMP.PROTOCOL_NUMBER);
        ipPacket.setSourceAddress(0x11111111);
        ipPacket.setDestinationAddress(0x21212121);
        Ethernet packet = new Ethernet();
        packet.setPayload(ipPacket);
        packet.setDestinationMACAddress(dstMac);
        packet.setSourceMACAddress(srcMac);
        packet.setEtherType(IPv4.ETHERTYPE);
        return packet;
    }

    static MidoMatch makeFlowMatch(MAC srcMac, MAC dstMac) {
        MidoMatch match = new MidoMatch();
        match.setDataLayerDestination(dstMac);
        match.setDataLayerSource(srcMac);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkDestination(0x21212121);
        match.setNetworkSource(0x11111111);
        match.setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        match.setTransportSource((short)ICMP.TYPE_ECHO_REQUEST);
        match.setTransportDestination((short)0);
        //match.setDataLayerVirtualLan((short)0);
        // Python sets dl_vlan=0xFFFF, but packets.Ethernet.vlanID is 0
        // for no VLAN in use.
        // TODO:  Which is really proper, 0 or 0xFFFF ?
        //match.setDataLayerVirtualLanPriorityCodePoint((byte)0);
        // match.setNetworkTypeOfService((byte)0);
        return match;
    }

    @Before
    public void setUp() throws java.lang.Exception {
        ovsdb = new MockOpenvSwitchDatabaseConnection();
        publicIp = IntIPv4.fromString("192.168.1.50");

        // Create portUuids:
        //      Seven random UUIDs, and an eighth being a dup of the seventh.
        portUuids = new UUID[] { UUID.randomUUID(), UUID.randomUUID(),
                                 UUID.randomUUID(), UUID.randomUUID(),
                                 UUID.randomUUID(), UUID.randomUUID(),
                                 UUID.randomUUID(), UUID.randomUUID() };
        portUuids[7] = portUuids[6];

        // Register local ports (ports 0, 1, 2) into datapath in ovsdb.
        ovsdb.setPortExternalId(dp_id, 0, "midonet", portUuids[0].toString());
        ovsdb.setPortExternalId(dp_id, 1, "midonet", portUuids[1].toString());
        ovsdb.setPortExternalId(dp_id, 2, "midonet", portUuids[2].toString());

        // Set up the (mock) ZooKeeper directories.
        String basePath = "/zk_root";
        ZkPathManager pathMgr = new ZkPathManager(basePath);
        MockDirectory zkDir = new MockDirectory();
        zkDir.add(basePath, null, CreateMode.PERSISTENT);
        Setup.createZkDirectoryStructure(zkDir, basePath);

        portLocMap = new PortToIntNwAddrMap(zkDir.getSubDirectory(
                                pathMgr.getVRNPortLocationsPath()));
        portLocMap.start();
        BridgeZkManager.BridgeConfig bcfg = new BridgeZkManager.BridgeConfig();
        BridgeZkManager bzkm = new BridgeZkManager(zkDir, basePath);
        UUID bridgeUUID = bzkm.create(bcfg);
        String macPortPath = pathMgr.getBridgeMacPortsPath(bridgeUUID);
        macPortMap = new MacPortMap(zkDir.getSubDirectory(macPortPath));
        macPortMap.start();
        PortSetMap portSetMap = new PortSetMap(zkDir, basePath);
        portSetMap.start();
        PortZkManager portMgr = new PortZkManager(zkDir, basePath);

        reactor = new MockReactor();

        controllerStub = new MockControllerStub();

        controller = new VRNController(
                /* datapathId */                dp_id,
                /* dir */                       zkDir,
                /* basePath */                  basePath,
                /* publicIp */                  publicIp,
                /* ovsdb */                     ovsdb,
                /* reactor */                   reactor,
                /* cache */                     null,
                /* externalIdKey */             "midonet",
                /* service */                   null);
        controller.setControllerStub(controllerStub);

        // Insert ports 3..8 into portLocMap and macPortMap.
        for (int i = 3; i < 8; i++) {
            portLocMap.put(portUuids[i], IntIPv4.fromString(peerStrList[i]));
            macPortMap.put(macList[i], portUuids[i]);
            log.info("Adding map MAC {} -> port {} -> loc {}",
                     new Object[] { macList[i], portUuids[i],
                                    peerStrList[i] });
        }

        OFFeaturesReply features = new OFFeaturesReply();
        // Start with an empty port list.
        ArrayList<OFPhysicalPort> portList = new ArrayList<OFPhysicalPort>();
        features.setPorts(portList);
        controllerStub.setFeatures(features);
        controller.onConnectionMade();

        // Populate phyPorts and add to controller.
        for (int i = 0; i < 8; i++) {
            portList.add(phyPorts[i]);
            phyPorts[i].setPortNumber((short)i);
            phyPorts[i].setHardwareAddress(macList[i].getAddress());
            IntIPv4 peerIP = IntIPv4.fromString(peerStrList[i]);
            // First three ports are local.  The rest are tunneled.
            phyPorts[i].setName(i < 3 ? "port" + Integer.toString(i)
                                      : controller.makeGREPortName(peerIP));
            if (i < 7) {
                PortDirectory.BridgePortConfig bpc =
                        new PortDirectory.BridgePortConfig(bridgeUUID);
                portMgr.create(bpc, portUuids[i]);
                portKeys[i] = bpc.greKey;
            }
            portSetMap.addIPv4Addr(bridgeUUID, peerIP);
            controller.onPortStatus(phyPorts[i], OFPortReason.OFPPR_ADD);
            // TODO: If we add the port to the ctrlr before we have its info
            // in ZK, we get a NoNode ZK exception and then the port's config
            // is not recognized when we later add it to ZK.  Fix this.
        }

        numPreinstalledFlows = controllerStub.addedFlows.size();
        // TODO: Verify the pre-installed flows are what they should be.

        tunnelID = bcfg.greKey;

        // Floods from an input port output to the two non-ingress local
        // ports, and the five tunnel ports.
        floodActionNoPort0 = new OFAction[8];
        floodActionNoPort0[0] = new NxActionSetTunnelKey32(tunnelID);
        floodActionNoPort0[1] = new OFActionOutput((short)1, (short)0);
        floodActionNoPort0[2] = new OFActionOutput((short)2, (short)0);
        floodActionNoPort0[3] = new OFActionOutput((short)3, (short)0);
        floodActionNoPort0[4] = new OFActionOutput((short)4, (short)0);
        floodActionNoPort0[5] = new OFActionOutput((short)5, (short)0);
        floodActionNoPort0[6] = new OFActionOutput((short)6, (short)0);
        floodActionNoPort0[7] = new OFActionOutput((short)7, (short)0);

        // Floods from a tunnel output to the three local ports only.
        floodActionLocalOnly = new OFAction[3];
        floodActionLocalOnly[0] = new OFActionOutput((short)0, (short)0);
        floodActionLocalOnly[1] = new OFActionOutput((short)1, (short)0);
        floodActionLocalOnly[2] = new OFActionOutput((short)2, (short)0);

        bridge = (Bridge)controller.vrn.getForwardingElementByPort(portUuids[0]);
    }

    void assertArrayEquals(Object[] a, Object[] b) {
        // junit's assertArrayEquals doesn't print out the array contents
        // on non-match.  Write our own which does.
        try {
            Assert.assertArrayEquals(a, b);
        } catch (AssertionError e) {
            log.error("Expected array: {}\nActual array: {}", a, b);
            throw e;
        }
    }

    void assertArrayEquals(byte[] a, byte[] b) {
        // junit's assertArrayEquals doesn't print out the array contents
        // on non-match.  Write our own which does.
        try {
            Assert.assertArrayEquals(a, b);
        } catch (AssertionError e) {
            log.error("Expected array: {}\nActual array: {}", a, b);
            throw e;
        }
    }

    void checkInstalledFlow(OFMatch expectedMatch, int idleTimeout,
                            int hardTimeoutMin, int hardTimeoutMax,
                            int priority, OFAction[] actions) {
        assertEquals(numPreinstalledFlows+1, controllerStub.addedFlows.size());
        MockControllerStub.Flow flow = controllerStub.addedFlows.get(
                                                numPreinstalledFlows);
        assertEquals(expectedMatch, flow.match);
        assertEquals(idleTimeout, flow.idleTimeoutSecs);
        assertThat((int)flow.hardTimeoutSecs,
                   greaterThanOrEqualTo(hardTimeoutMin));
        assertThat((int)flow.hardTimeoutSecs,
                   lessThanOrEqualTo(hardTimeoutMax));
        assertEquals(priority, flow.priority);
        assertArrayEquals(actions, flow.actions.toArray());
    }

    void checkSentPacket(int bufferId, short inPort, OFAction[] actions,
                         byte[] data) {
        assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet sentPacket =
                controllerStub.sentPackets.get(0);
        assertEquals(bufferId, sentPacket.bufferId);
        assertEquals(inPort, sentPacket.inPort);
        assertArrayEquals(actions, sentPacket.actions.toArray());
        assertArrayEquals(data, sentPacket.data);
    }

    @Test
    public void testPacketInWithMalformedEmptyPacket() {
        OFAction[] expectActions = { };
        controller.onPacketIn(14, 13, (short)0, new byte[]{});
        assertEquals(0, controllerStub.sentPackets.size());
    }

    @Test
    public void testPacketInWithNovelMac() {
        MidoMatch expectedMatch = flowmatch01.clone();
        short inputPort = 0;
        expectedMatch.setInputPort(inputPort);
        controller.onPacketIn(14, 13, inputPort, packet01.serialize());
        // Verify it floods to all ports except inputport (0)
        checkInstalledFlow(expectedMatch, normalIdle, 0, 0, normalPriority,
                           floodActionNoPort0);
        checkSentPacket(14, (short)-1, floodActionNoPort0, new byte[] {});
    }

    @Test
    public void testMulticastLocalInPort() {
        final Ethernet packet = packet0MC;
        short inPortNum = 0;
        MidoMatch expectedMatch = flowmatch0MC.clone();
        expectedMatch.setInputPort(inPortNum);
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        // Verify it floods to all ports except inputport (0)
        checkInstalledFlow(expectedMatch, normalIdle, 0, 0, normalPriority,
                           floodActionNoPort0);
        checkSentPacket(14, (short)-1, floodActionNoPort0, new byte[] {});
    }

    @Test
    public void testMulticastTunnelInPort() {
        final Ethernet packet = packet0MC;
        short inPortNum = 5;
        MidoMatch expectedMatch = flowmatch0MC.clone();
        expectedMatch.setInputPort(inPortNum);
        controller.onPacketIn(14, 13, inPortNum, packet.serialize(), tunnelID);
        checkInstalledFlow(expectedMatch, normalIdle, 0, 0, normalPriority,
                           floodActionLocalOnly);
        checkSentPacket(14, (short)-1, floodActionLocalOnly, new byte[] {});
    }

    @Test
    public void testRemoteMACWithTunnel() {
        final Ethernet packet = packet13;
        short inPortNum = 1;
        short outPortNum = 3;
        MidoMatch expectMatch = flowmatch13.clone();
        expectMatch.setInputPort(inPortNum);
        OFAction[] expectAction = { new NxActionSetTunnelKey32(portKeys[3]),
                                    new OFActionOutput(outPortNum, (short)0) };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectMatch, normalIdle, 0, 0, normalPriority, expectAction);
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
    }

    @Test
    public void testRemoteMACNoTunnel() {
        final Ethernet packet = packet13;
        short inPortNum = 1;
        short outPortNum = 3;
        MidoMatch expectMatch = flowmatch13.clone();
        expectMatch.setInputPort(inPortNum);
        // Drop if there's no tunnel.
        OFAction[] expectAction = { };
        controller.onPortStatus(phyPorts[3], OFPortReason.OFPPR_DELETE);
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectMatch, 0, tempDropTime, tempDropTime,
                           normalPriority, expectAction);
        assertEquals(0, controllerStub.sentPackets.size());
    }

    @Test
    public void testTunnelInForLocalPort() {
        short inPortNum = 4;
        final Ethernet packet = packet41;
        MidoMatch expectMatch = flowmatch41.clone();
        expectMatch.setInputPort(inPortNum);
        OFAction[] expectAction = { new OFActionOutput((short)1, (short)0) };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize(), portKeys[1]);
        // Verify flowmatch and sent to port 1.
        checkInstalledFlow(expectMatch, normalIdle, 0, 0, normalPriority,
                           expectAction);
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
    }

    @Test
    public void testTunnelInForRemotePort() {
        short inPortNum = 4;
        final Ethernet packet = packet13;
        MidoMatch expectMatch = flowmatch13.clone();
        expectMatch.setInputPort(inPortNum);
        OFAction[] expectAction = { };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize(), portKeys[3]);
        // Verify drop rule & no packet sent.
        checkInstalledFlow(expectMatch, 0, tempDropTime, tempDropTime,
                           normalPriority, expectAction);
        assertEquals(0, controllerStub.sentPackets.size());
    }

    @Test
    public void testFlowInvalidatePortUnreachable()
                throws KeeperException, InterruptedException {
        int oldDelCount = controllerStub.deletedFlows.size();
        log.info("Start of testFlowInvalidatePortUnreachable: del flow " +
                 "count is {}", oldDelCount);
        controller.onPacketIn(14, 13, (short)0, packet03.serialize());
        controller.onPacketIn(14, 13, (short)1, packet13.serialize());
        controller.onPacketIn(14, 13, (short)0, packet04.serialize());

        OFAction[][] expectedActions = {
                { new NxActionSetTunnelKey32(portKeys[3]),
                  new OFActionOutput((short)3, (short)0) },
                { new NxActionSetTunnelKey32(portKeys[3]),
                  new OFActionOutput((short)3, (short)0) },
                { new NxActionSetTunnelKey32(portKeys[4]),
                  new OFActionOutput((short)4, (short)0) },
        };

        assertEquals(numPreinstalledFlows+3, controllerStub.addedFlows.size());
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(expectedActions[i],
                              controllerStub.addedFlows.get(
                                                numPreinstalledFlows+i)
                                            .actions.toArray());
        }

        // TODO: Old flowmatch-based invalidation no longer done.  Test
        // whatever installed-flow invalidation replaced it.
        assertEquals(oldDelCount, controllerStub.deletedFlows.size());
        if (true)
            return;
        // ALL CODE AFTER THIS POINT UNREACHABLE -- WAS TESTING THE OLD
        // INVALIDATION LOGIC
        assertEquals(oldDelCount+4, controllerStub.deletedFlows.size());
        MidoMatch expectedDeletes[] = {
                new MidoMatch(), new MidoMatch(),
                new MidoMatch(), new MidoMatch() };
        expectedDeletes[0].setDataLayerSource(macList[0]);
        expectedDeletes[1].setDataLayerDestination(macList[0]);
        expectedDeletes[2].setDataLayerSource(macList[1]);
        expectedDeletes[3].setDataLayerDestination(macList[1]);
        for (int i = 0; i < 4; i++) {
            assertEquals(expectedDeletes[i],
                         controllerStub.deletedFlows.get(oldDelCount+i).match);
        }
        oldDelCount += 4;
        // Trigger a notification that the remote port on the other
        // end of the tunnel has gone away.
        log.info("Removing port {} from portLocMap", portUuids[3]);
        portLocMap.remove(portUuids[3]);
        assertEquals(oldDelCount+1, controllerStub.deletedFlows.size());
        MidoMatch expectedMatch = new MidoMatch();
        expectedMatch.setDataLayerDestination(macList[3]);
        assertEquals(expectedMatch,
                     controllerStub.deletedFlows.get(oldDelCount).match);
        log.info("Removing port {} from portLocMap", portUuids[4]);
        portLocMap.remove(portUuids[4]);
        assertEquals(oldDelCount+2, controllerStub.deletedFlows.size());
        expectedMatch.setDataLayerDestination(macList[4]);
        assertEquals(expectedMatch,
                     controllerStub.deletedFlows.get(oldDelCount+1).match);
    }

    @Test @Ignore // TODO: The invalidation logic has changed -- update this test.
    public void testFlowInvalidatePortMoves()
                throws KeeperException, InterruptedException {
        int oldDelCount = controllerStub.deletedFlows.size();
        short inPortNum = 1;
        short outPortNum = 3;
        final Ethernet packet = packet13;
        MidoMatch expectMatch = flowmatch13.clone();
        expectMatch.setInputPort(inPortNum);
        OFAction[] expectAction = { new OFActionOutput(outPortNum, (short)0) };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectMatch, normalIdle, 0, 0, normalPriority, expectAction);
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});

        assertEquals(oldDelCount+2, controllerStub.deletedFlows.size());
        MidoMatch expectSrcDelete = new MidoMatch();
        expectSrcDelete.setDataLayerSource(macList[1]);
        MidoMatch expectDstDelete = new MidoMatch();
        expectDstDelete.setDataLayerDestination(macList[1]);
        assertEquals(expectSrcDelete,
                     controllerStub.deletedFlows.get(oldDelCount).match);
        assertEquals(expectDstDelete,
                     controllerStub.deletedFlows.get(oldDelCount+1).match);
        oldDelCount += 2;

        // Move the port, check that the controller removed the flow.
        portLocMap.put(portUuids[3], IntIPv4.fromString("1.2.3.4"));
        assertTrue(oldDelCount < controllerStub.deletedFlows.size());
        MidoMatch expectedMatch = new MidoMatch();
        expectedMatch.setDataLayerDestination(macList[3]);
        for (int i = oldDelCount; i < controllerStub.deletedFlows.size(); i++) {
            assertEquals(expectedMatch,
                         controllerStub.deletedFlows.get(i).match);
        }
    }

    @Test
    public void testLocalMacLearned() {
        short inPortNum = 0;
        MAC mac = macList[inPortNum];
        assertNull(macPortMap.get(mac));

        short outPortNum = 1;
        MidoMatch expectMatch = flowmatch01.clone();
        expectMatch.setInputPort(inPortNum);
        controller.onPacketIn(14, 13, inPortNum, packet01.serialize());
        checkInstalledFlow(expectMatch, normalIdle, 0, 0, normalPriority,
                           floodActionNoPort0);
        checkSentPacket(14, (short)-1, floodActionNoPort0, new byte[] {});

        // Verify that MAC was learned.
        assertEquals(portUuids[inPortNum], macPortMap.get(mac));

        // Send packet to learned MAC.
        inPortNum = 1;
        outPortNum = 0;
        expectMatch = flowmatch10.clone();
        expectMatch.setInputPort(inPortNum);
        OFAction[] expectAction = new OFAction[] {
                new OFActionOutput(outPortNum, (short)0) };
        controllerStub.addedFlows.clear();
        numPreinstalledFlows = 0;
        controllerStub.sentPackets.clear();
        controller.onPacketIn(14, 13, inPortNum, packet10.serialize());
        checkInstalledFlow(expectMatch, normalIdle, 0, 0, normalPriority,
                           expectAction);
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
    }

    @Test
    public void testUnmappedPortUuid()
                throws KeeperException, InterruptedException {
        short inPortNum = 0;
        short outPortNum = 4;
        final Ethernet packet = packet04;
        MidoMatch expectMatch = flowmatch04.clone();
        expectMatch.setInputPort(inPortNum);
        OFAction[] expectAction = {
                 new NxActionSetTunnelKey32(portKeys[outPortNum]),
                 new OFActionOutput(outPortNum, (short)0) };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectMatch, normalIdle, 0, 0, normalPriority,
                           expectAction);
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});

        // Remove the port->location mapping for the remote port and verify
        // that the new packets generate drop rules.
        // TODO: This should generate a flood.  Fix it.
        portLocMap.remove(portUuids[outPortNum]);
        controllerStub.addedFlows.clear();
        numPreinstalledFlows = 0;
        controllerStub.sentPackets.clear();

        expectAction = new OFAction[] { };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectMatch, 0, tempDropTime, tempDropTime,
                           normalPriority, expectAction);
        assertEquals(0, controllerStub.sentPackets.size());
    }

    @Test
    public void testInvalidateDropFlowsReachablePort()
                throws KeeperException, InterruptedException {
        portLocMap.remove(portUuids[4]);
        short inPortNum = 0;
        short outPortNum = 4;
        final Ethernet packet = packet04;
        MidoMatch expectMatch = flowmatch04.clone();
        expectMatch.setInputPort(inPortNum);
        OFAction[] expectAction = { };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectMatch, 0, tempDropTime, tempDropTime,
                           normalPriority, expectAction);
        assertEquals(0, controllerStub.sentPackets.size());

        // Add the port->loc mapping, verify that the flow is removed.
        controllerStub.deletedFlows.clear();
        MidoMatch removeMatchDst = new MidoMatch();
        removeMatchDst.setDataLayerDestination(macList[outPortNum]);
        portLocMap.put(portUuids[outPortNum],
                       IntIPv4.fromString(peerStrList[outPortNum]));
        // Invalidation has changed.  TODO: Test the new way.
        /*
        assertThat(controllerStub.deletedFlows.size(), greaterThan(0));
        for (int i = 0; i < controllerStub.deletedFlows.size(); i++) {
            assertEquals(removeMatchDst,
                         controllerStub.deletedFlows.get(i).match);
        }
        */
    }

    @Test
    public void testBadlyMappedPortUuid()
                throws KeeperException, InterruptedException {
        MAC srcMac = MAC.fromString("00:AA:AA:AA:AA:00");
        MAC dstMac = MAC.fromString("00:AA:AA:AA:AA:01");
        UUID dstUuid = UUID.fromString("251cbfb6-9ca1-4685-9320-c7203c4ffff2");
        macPortMap.put(dstMac, dstUuid);
        portLocMap.put(dstUuid, publicIp);
        Ethernet packet = makePacket(srcMac, dstMac);
        MidoMatch flowmatch = makeFlowMatch(srcMac, dstMac);

        // Send the packet from both local and tunnel ports.
        for (short inPortNum : new short[] { 2, 4 }) {
            controllerStub.addedFlows.clear();
            numPreinstalledFlows = 0;
            controllerStub.sentPackets.clear();
            controller.onPacketIn(14, 13, inPortNum, packet.serialize());
            // Mapping is bad, so expect a drop without installing a drop rule.
            assertEquals(0, controllerStub.addedFlows.size());
            assertEquals(0, controllerStub.sentPackets.size());
        }
    }

    @Test
    public void testFlowCountDelays() {
        short inPortNum = 0;
        final int numFlows = 3;
        for (int i = 0; i < numFlows; i++) {
            controller.onPacketIn(14, 13, inPortNum, packet04.serialize());
        }
        assertEquals(numPreinstalledFlows+numFlows,
                     controllerStub.addedFlows.size());

        assertEquals(portUuids[inPortNum], macPortMap.get(macList[inPortNum]));
        for (int i = 0; i < numFlows; i++) {
            reactor.incrementTime(timeout_ms, TimeUnit.MILLISECONDS);
            OFMatch match = controllerStub.addedFlows.get(
                                numPreinstalledFlows+i).match;
            controller.onFlowRemoved(match, 0, (short)1000,
                        OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT,
                        timeout_ms/1000, 0, (short)(timeout_ms/1000), 123, 456);
            assertEquals(portUuids[inPortNum],
                         macPortMap.get(macList[inPortNum]));
        }
        reactor.incrementTime(timeout_ms-1, TimeUnit.MILLISECONDS);
        assertEquals(portUuids[inPortNum], macPortMap.get(macList[inPortNum]));

        reactor.incrementTime(2, TimeUnit.MILLISECONDS);
        assertNull(macPortMap.get(macList[inPortNum]));

        // Rediscover a mapping after the last flow has been removed.
        // ref 0 -> 1
        controller.onPacketIn(14, 13, inPortNum, packet01.serialize());
        reactor.incrementTime(timeout_ms*3, TimeUnit.MILLISECONDS);
        assertEquals(portUuids[inPortNum], macPortMap.get(macList[inPortNum]));
        // ref 1 -> 0
        OFMatch match = controllerStub.addedFlows.get(
                                controllerStub.addedFlows.size()-1).match;
        controller.onFlowRemoved(match, 0, (short)1000,
                        OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT,
                        timeout_ms/1000, 0, (short)(timeout_ms/1000), 123, 456);
        reactor.incrementTime(timeout_ms-1, TimeUnit.MILLISECONDS);
        assertEquals(portUuids[inPortNum], macPortMap.get(macList[inPortNum]));
        // ref 0 -> 1:  Cancels delayed delete.
        controller.onPacketIn(14, 13, inPortNum, packet0MC.serialize());
        reactor.incrementTime(timeout_ms*2, TimeUnit.MILLISECONDS);
        assertEquals(portUuids[inPortNum], macPortMap.get(macList[inPortNum]));
        // ref 1 -> 0
        controller.onFlowRemoved(match, 0, (short)1000,
                        OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT,
                        timeout_ms/1000, 0, (short)(timeout_ms/1000), 123, 456);
        reactor.incrementTime(timeout_ms-1, TimeUnit.MILLISECONDS);
        assertEquals(portUuids[inPortNum], macPortMap.get(macList[inPortNum]));
        reactor.incrementTime(2, TimeUnit.MILLISECONDS);
        assertNull(macPortMap.get(macList[inPortNum]));
    }

    @Test
    public void testFlowCountExpireOnTimeoutThenClear() {
        controllerStub.addedFlows.clear();
        short inPortNum = 0;
        final int numFlows = 3;
        for (int i = 0; i < numFlows; i++) {
            controller.onPacketIn(14, 13, inPortNum, packet04.serialize());
        }
        assertEquals(numFlows, controllerStub.addedFlows.size());
        assertEquals(1, bridge.flowCount.size());
        Object key = bridge.flowCount.keySet().toArray()[0];
        assertEquals(new Integer(numFlows), bridge.flowCount.get(key));

        assertEquals(portUuids[inPortNum], macPortMap.get(macList[inPortNum]));
        for (int i = 0; i < numFlows; i++) {
            reactor.incrementTime(timeout_ms, TimeUnit.MILLISECONDS);
            OFMatch match = controllerStub.addedFlows.get(i).match;
            controller.onFlowRemoved(match, 0, (short)1000,
                        OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT,
                        timeout_ms/1000, 0, (short)(timeout_ms/1000), 123, 456);
            assertEquals(portUuids[inPortNum],
                         macPortMap.get(macList[inPortNum]));
        }

        assertEquals(portUuids[inPortNum], macPortMap.get(macList[inPortNum]));

        assertEquals(0, bridge.flowCount.size());
        // The MAC should be in the delayedDeletes list.
        assertEquals(1, bridge.delayedDeletes.size());
        //assertEquals(macList[0], bridge.delayedDeletes.get(0).___
        // It appears there's no way to get at the Runnable associated
        // with a ScheduledFuture?
        controller.clear();
        assertEquals(0, bridge.delayedDeletes.size());
    }

    @Test
    public void testFlowCountExpireOnClear() {
        short inPortNum = 0;
        controller.onPacketIn(14, 13, inPortNum, packet04.serialize());
        assertEquals(1, bridge.flowCount.size());
        controller.clear();
        assertEquals(0, bridge.flowCount.size());
    }

    @Test
    public void testMacChangesPort() {
        controllerStub.addedFlows.clear();
        controller.onPacketIn(14, 13, (short)1, packet10.serialize());
        assertEquals(portUuids[1], macPortMap.get(macList[1]));
        // MAC moves to port 2.
        controller.onPacketIn(14, 13, (short)2, packet10.serialize());
        assertEquals(portUuids[2], macPortMap.get(macList[1]));
        OFMatch match1 = controllerStub.addedFlows.get(0).match;
        assertEquals(1, match1.getInputPort());
        assertEquals(macList[1], new MAC(match1.getDataLayerSource()));
        controller.onFlowRemoved(match1, 0, (short)1000,
                        OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT,
                        timeout_ms/1000, 0, (short)(timeout_ms/1000), 123, 456);
        reactor.incrementTime(timeout_ms+1, TimeUnit.MILLISECONDS);
        assertEquals(portUuids[2], macPortMap.get(macList[1]));

        OFMatch match2 = controllerStub.addedFlows.get(1).match;
        assertEquals(2, match2.getInputPort());
        assertEquals(macList[1], new MAC(match2.getDataLayerSource()));
        controller.onFlowRemoved(match2, 0, (short)1000,
                        OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT,
                        timeout_ms/1000, 0, (short)(timeout_ms/1000), 123, 456);
        reactor.incrementTime(timeout_ms+1, TimeUnit.MILLISECONDS);
        assertNull(macPortMap.get(macList[1]));
    }

    @Test
    public void testLearnedMacPortDeleted() {
        controller.onPacketIn(14, 13, (short)1, packet10.serialize());
        assertEquals(portUuids[1], macPortMap.get(macList[1]));
        controller.onPacketIn(14, 13, (short)0, packet0MC.serialize());
        assertEquals(portUuids[0], macPortMap.get(macList[0]));

        // Delete port 1.
        controller.onPortStatus(phyPorts[1], OFPortReason.OFPPR_DELETE);
        assertNull(macPortMap.get(macList[1]));
        assertEquals(portUuids[0], macPortMap.get(macList[0]));

        // Delete port 0.
        controller.onPortStatus(phyPorts[0], OFPortReason.OFPPR_DELETE);
        assertNull(macPortMap.get(macList[1]));
        assertNull(macPortMap.get(macList[0]));
    }

    @Test
    public void testNotLearningMac() {
        assertNull(macPortMap.get(macList[1]));
        controller.onPacketIn(14, 13, (short)1, packet13.serialize());
        assertEquals(portUuids[1], macPortMap.get(macList[1]));

        // Check multicast-source
        controller.onPacketIn(14, 13, (short)2, packetMC0.serialize());
        assertNull(macPortMap.get(macList[8]));

        // Check unicast from tunnel port.
        controller.onPacketIn(14, 13, (short)4, packet01.serialize());
        assertNull(macPortMap.get(macList[0]));
    }

    @Test
    public void testMulticastSrcMacAddrDropped() {
        short inPortNum = 2;
        short outPortNum = 4;
        final Ethernet packet = packetMC0;
        MidoMatch expectMatch = flowmatchMC0.clone();
        expectMatch.setInputPort(inPortNum);
        OFAction[] expectAction = { };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectMatch, 0, tempDropTime, tempDropTime,
                           normalPriority, expectAction);
        assertEquals(0, controllerStub.sentPackets.size());
    }

    @Test
    public void testClear() {
        controller.onPacketIn(14, 13, (short)0, packet04.serialize());
        controller.onPacketIn(14, 13, (short)1, packet10.serialize());
        assertEquals(portUuids[0], macPortMap.get(macList[0]));
        assertEquals(portUuids[1], macPortMap.get(macList[1]));
        controller.clear();
        assertNull(macPortMap.get(macList[0]));
        assertNull(macPortMap.get(macList[1]));
    }

    @Test
    public void testMacPortUpdateInvalidatesFlows()
                throws KeeperException, InterruptedException {
        controllerStub.addedFlows.clear();
        controller.onPacketIn(14, 13, (short)5, packet70.serialize(), tunnelID);
        checkSentPacket(14, (short)-1, floodActionLocalOnly, new byte[] {});
        assertEquals(1, controllerStub.addedFlows.size());
        assertArrayEquals(floodActionLocalOnly,
                          controllerStub.addedFlows.get(0).actions.toArray());

        // Send a packet[src=0 dst=1] to port 0.
        controllerStub.sentPackets.clear();
        controllerStub.deletedFlows.clear();
        controller.onPacketIn(14, 13, (short)0, packet01.serialize());
        checkSentPacket(14, (short)-1, floodActionNoPort0, new byte[] {});
        // New flow added, old flow removed.
        assertEquals(2, controllerStub.addedFlows.size());
        assertArrayEquals(floodActionNoPort0,
                          controllerStub.addedFlows.get(1).actions.toArray());
        MidoMatch expectMatch = new MidoMatch();
        expectMatch.setDataLayerDestination(macList[0]);
        // TODO: Check the new invalidation and fix this and the others.
        /*
        assertThat(controllerStub.deletedFlows.size(), greaterThan(0));
        assertEquals(expectMatch,
                     controllerStub.deletedFlows.get(
                                controllerStub.deletedFlows.size()-1).match);
        */

        // Send a packet[src=1 dst=0] to port 1.
        controllerStub.sentPackets.clear();
        controllerStub.deletedFlows.clear();
        OFAction[] expectAction0 = new OFAction[] {
                                new OFActionOutput((short)0, (short)0) };
        controller.onPacketIn(14, 13, (short)1, packet10.serialize());
        checkSentPacket(14, (short)-1, expectAction0, new byte[] {});
        // New flow added, old flow removed.
        assertEquals(3, controllerStub.addedFlows.size());
        assertArrayEquals(expectAction0,
                          controllerStub.addedFlows.get(2).actions.toArray());
        expectMatch.setDataLayerDestination(macList[1]);
        /* // TODO: Invalidation
        assertTrue(0 < controllerStub.deletedFlows.size());
        assertEquals(expectMatch,
                     controllerStub.deletedFlows.get(
                                controllerStub.deletedFlows.size()-1).match);
        */

        // Send a packet[src=0 dst=4] to port 1.  (MAC 0 moves from port 0
        // to port 1.)
        controllerStub.sentPackets.clear();
        controllerStub.deletedFlows.clear();
        OFAction[] expectAction4 = new OFAction[] {
                                new NxActionSetTunnelKey32(portKeys[4]),
                                new OFActionOutput((short)4, (short)0) };
        controller.onPacketIn(14, 13, (short)1, packet04.serialize());
        checkSentPacket(14, (short)-1, expectAction4, new byte[] {});
        // New flow added, invalidation of srcMAC = MAC 0 or dstMAC = MAC 0.
        assertEquals(4, controllerStub.addedFlows.size());
        assertArrayEquals(expectAction4,
                          controllerStub.addedFlows.get(3).actions.toArray());
        /* // TODO: Invalidation
        assertEquals(2, controllerStub.deletedFlows.size());
        expectMatch.setDataLayerDestination(macList[0]);
        assertEquals(expectMatch, controllerStub.deletedFlows.get(1).match);
        expectMatch = new MidoMatch();
        expectMatch.setDataLayerSource(macList[0]);
        assertEquals(expectMatch, controllerStub.deletedFlows.get(0).match);
        */

        // MAC 0 moves to remote port 5.
        controllerStub.deletedFlows.clear();
        macPortMap.put(macList[0], portUuids[5]);
        // Flows to or from MAC 0 invalidated.
        for (MockControllerStub.Flow flow : controllerStub.deletedFlows) {
            log.info("Deleted flow: {}", flow);
        }
        /* // TODO: Invalidation
        assertTrue(2 <= controllerStub.deletedFlows.size());
        assertEquals(expectMatch, controllerStub.deletedFlows.get(0).match);
        expectMatch = new MidoMatch();
        expectMatch.setDataLayerDestination(macList[0]);
        assertEquals(expectMatch, controllerStub.deletedFlows.get(1).match);
        */

        // Send a packet[src=1 dst=0] to port 1.
        controllerStub.sentPackets.clear();
        OFAction[] expectAction5 = new OFAction[] {
                                new NxActionSetTunnelKey32(portKeys[5]),
                                new OFActionOutput((short)5, (short)0) };
        controller.onPacketIn(14, 13, (short)1, packet10.serialize());
        checkSentPacket(14, (short)-1, expectAction5, new byte[] {});
        // New flow to tunnel port 5 added.
        assertEquals(5, controllerStub.addedFlows.size());
        assertArrayEquals(expectAction5,
                          controllerStub.addedFlows.get(4).actions.toArray());

        // Send a packet[src=0 dst=1] to port 5.
        controllerStub.sentPackets.clear();
        OFAction[] expectAction1 = new OFAction[] {
                                new OFActionOutput((short)1, (short)0) };
        controller.onPacketIn(14, 13, (short)5, packet01.serialize(),
                              portKeys[1]);
        checkSentPacket(14, (short)-1, expectAction1, new byte[] {});
        assertEquals(6, controllerStub.addedFlows.size());
        assertArrayEquals(expectAction1,
                          controllerStub.addedFlows.get(5).actions.toArray());

        // Send a packet[src=1 dst=3] to port 1.
        controllerStub.sentPackets.clear();
        OFAction[] expectAction3 = new OFAction[] {
                                new NxActionSetTunnelKey32(portKeys[3]),
                                new OFActionOutput((short)3, (short)0) };
        controller.onPacketIn(14, 13, (short)1, packet13.serialize());
        checkSentPacket(14, (short)-1, expectAction3, new byte[] {});
        assertEquals(7, controllerStub.addedFlows.size());
        assertArrayEquals(expectAction3,
                          controllerStub.addedFlows.get(6).actions.toArray());

        // Remove MAC 0 from macPortMap.
        controllerStub.deletedFlows.clear();
        macPortMap.remove(macList[0]);
        // Only flows with dstMAC = MAC 0 should be invalidated.  Flows with
        // srcMAC = MAC 0 should still be valid.
        /* // TODO: Invalidation
        assertEquals(1, controllerStub.deletedFlows.size());
        expectMatch = new MidoMatch();
        expectMatch.setDataLayerDestination(macList[0]);
        assertEquals(expectMatch, controllerStub.deletedFlows.get(0).match);
        */
    }

    @Ignore // TODO: Invalidation
    @Test
    public void testPortLocUpdateInvalidatesFlows()
                throws KeeperException, InterruptedException {
        controller.onPacketIn(14, 13, (short)0, packet04.serialize());
        controller.onPacketIn(14, 13, (short)1, packet15.serialize());
        controller.onPacketIn(14, 13, (short)2, packet23.serialize());
        controller.onPacketIn(14, 13, (short)2, packet25.serialize());

        OFAction expectedActions[][] = {
                { new OFActionOutput((short)4, (short)0) },
                { new OFActionOutput((short)5, (short)0) },
                { new OFActionOutput((short)3, (short)0) },
                { new OFActionOutput((short)5, (short)0) } };
        MidoMatch expectedMatches[] = {
                flowmatch04.clone(), flowmatch15.clone(),
                flowmatch23.clone(), flowmatch25.clone() };
        expectedMatches[0].setInputPort((short)0);
        expectedMatches[1].setInputPort((short)1);
        expectedMatches[2].setInputPort((short)2);
        expectedMatches[3].setInputPort((short)2);

        assertEquals(4, controllerStub.addedFlows.size());
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(expectedActions[i],
                              controllerStub.addedFlows.get(i).actions.toArray()
                             );
            assertEquals(expectedMatches[i],
                         controllerStub.addedFlows.get(i).match);
        }

        // Move portUuid 5 to peer 3.
        controllerStub.deletedFlows.clear();
        portLocMap.put(portUuids[5], IntIPv4.fromString(peerStrList[3]));
        // Flows to MAC 5 should have been invalidated.
        MidoMatch expectMatch = new MidoMatch();
        expectMatch.setDataLayerDestination(macList[5]);
        assertTrue(flowListContainsMatch(controllerStub.deletedFlows,
                                         expectMatch));

        // Now packet25 should go to peer 3 not peer 5.
        controller.onPacketIn(14, 13, (short)2, packet25.serialize());
        assertEquals(5, controllerStub.addedFlows.size());
        assertArrayEquals(new OFAction[] {
                                  new OFActionOutput((short)3, (short)0) },
                          controllerStub.addedFlows.get(4).actions.toArray());
        expectMatch = flowmatch25.clone();
        expectMatch.setInputPort((short)2);
        assertEquals(expectMatch, controllerStub.addedFlows.get(4).match);

        // Delete the portLocMap entry for portUuid[5].  Flows to MAC 5 should
        // be invalidated.
        controllerStub.deletedFlows.clear();
        portLocMap.remove(portUuids[5]);
        expectMatch = new MidoMatch();
        expectMatch.setDataLayerDestination(macList[5]);
        assertTrue(flowListContainsMatch(controllerStub.deletedFlows,
                                         expectMatch));

        // Now packet25 should go to ALL.
        controller.onPacketIn(14, 13, (short)2, packet25.serialize());
        assertEquals(6, controllerStub.addedFlows.size());
        assertArrayEquals(new OFAction[] { OUTPUT_ALL_ACTION },
                          controllerStub.addedFlows.get(5).actions.toArray());
        expectMatch = flowmatch25.clone();
        expectMatch.setInputPort((short)2);
        assertEquals(expectMatch, controllerStub.addedFlows.get(5).match);

        // Re-add portUuid 5 at peer 5.
        controllerStub.deletedFlows.clear();
        portLocMap.put(portUuids[5], IntIPv4.fromString(peerStrList[5]));
        // Flows to MAC 5 should have been invalidated.
        expectMatch = new MidoMatch();
        expectMatch.setDataLayerDestination(macList[5]);
        assertTrue(flowListContainsMatch(controllerStub.deletedFlows,
                                         expectMatch));

        controllerStub.deletedFlows.clear();
        portLocMap.remove(portUuids[4]);
        expectMatch.setDataLayerDestination(macList[4]);
        assertTrue(flowListContainsMatch(controllerStub.deletedFlows,
                                         expectMatch));
    }

    boolean flowListContainsMatch(List<MockControllerStub.Flow> flowList,
                                  OFMatch match) {
        for (MockControllerStub.Flow flow : flowList)
            if (flow.match.equals(match))
                return true;

        return false;
    }

    @Ignore // TODO: Invalidation.
    @Test
    public void testNontunnelPortDeleteInvalidatesFlows() {
        controller.onPacketIn(14, 13, (short)0, packet04.serialize());
        assertEquals(portUuids[0], macPortMap.get(macList[0]));
        MAC newMac = MAC.fromString("00:AA:AA:AA:22:22");
        final Ethernet packet = makePacket(newMac, macList[5]);
        MidoMatch flowmatch = makeFlowMatch(newMac, macList[5]);
        controller.onPacketIn(14, 13, (short)0, packet.serialize());
        assertEquals(portUuids[0], macPortMap.get(newMac));

        // Send several packets to MAC 0.
        controller.onPacketIn(14, 13, (short)1, packet10.serialize());
        controller.onPacketIn(14, 13, (short)2, packet20.serialize());
        controller.onPacketIn(14, 13, (short)7, packet70.serialize());

        OFAction expectedActions[][] = {
                { new OFActionOutput((short)4, (short)0) },
                { new OFActionOutput((short)5, (short)0) },
                { new OFActionOutput((short)0, (short)0) },
                { new OFActionOutput((short)0, (short)0) },
                { new OFActionOutput((short)0, (short)0) } };
        MidoMatch expectedMatches[] = {
                flowmatch04.clone(), flowmatch, flowmatch10.clone(),
                flowmatch20.clone(), flowmatch70.clone() };
        short flowMatchInPorts[] = { 0, 0, 1, 2, 7 };
        for (int i = 0; i < 5; i++) {
            expectedMatches[i].setInputPort(flowMatchInPorts[i]);
        }

        assertEquals(5, controllerStub.addedFlows.size());
        for (int i = 0; i < 5; i++) {
            assertArrayEquals(expectedActions[i],
                              controllerStub.addedFlows.get(i).actions.toArray()
                             );
            assertEquals(expectedMatches[i],
                         controllerStub.addedFlows.get(i).match);
        }

        // Delete port 0.
        controllerStub.deletedFlows.clear();
        controller.onPortStatus(phyPorts[0], OFPortReason.OFPPR_DELETE);

        assertNull(macPortMap.get(macList[0]));
        assertNull(macPortMap.get(newMac));

        MidoMatch expectedMatchSrc = new MidoMatch();
        MidoMatch expectedMatchDst = new MidoMatch();
        expectedMatchSrc.setDataLayerSource(macList[0]);
        expectedMatchDst.setDataLayerDestination(macList[0]);
        assertTrue(flowListContainsMatch(controllerStub.deletedFlows,
                                         expectedMatchSrc));
        assertTrue(flowListContainsMatch(controllerStub.deletedFlows,
                                         expectedMatchDst));
    }

    @Test
    public void testTunnelRemovalAndReaddition() {
        controllerStub.addedFlows.clear();
        controller.onPacketIn(13, 12, (short)0, packet04.serialize());
        controller.onPacketIn(13, 12, (short)1, packet15.serialize());
        controller.onPacketIn(13, 12, (short)2, packet26.serialize());
        controller.onPacketIn(13, 12, (short)2, packet27.serialize());

        OFAction expectedActions[][] = {
                { new NxActionSetTunnelKey32(portKeys[4]),
                  new OFActionOutput((short)4, (short)0) },
                { new NxActionSetTunnelKey32(portKeys[5]),
                  new OFActionOutput((short)5, (short)0) },
                { new NxActionSetTunnelKey32(portKeys[6]),
                  new OFActionOutput((short)7, (short)0) },
                { new NxActionSetTunnelKey32(portKeys[6]),
                  new OFActionOutput((short)7, (short)0) },
              };
        assertEquals(4, controllerStub.addedFlows.size());
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(expectedActions[i],
                              controllerStub.addedFlows.get(i).actions.toArray()                             );
        }
        MockControllerStub.Flow flow04 = controllerStub.addedFlows.get(0);
        MockControllerStub.Flow flow15 = controllerStub.addedFlows.get(1);
        MockControllerStub.Flow flow26 = controllerStub.addedFlows.get(2);
        MockControllerStub.Flow flow27 = controllerStub.addedFlows.get(3);

        controllerStub.deletedFlows.clear();
        controller.onPortStatus(phyPorts[7], OFPortReason.OFPPR_DELETE);
        log.debug("testTunnelRemovalAndReaddition: {} flows deleted.",
                  controllerStub.deletedFlows.size());
        assertEquals(2, controllerStub.deletedFlows.size());
        log.debug("testTunnelRemovalAndReaddition: First deleted flow: {}",
                  controllerStub.deletedFlows.get(0));
        log.debug("testTunnelRemovalAndReaddition: Second deleted flow: {}",
                  controllerStub.deletedFlows.get(1));

        // Deleted flows based on input & output ports.
        MockControllerStub.Flow expectedFlow1 = new MockControllerStub.Flow(
                new OFMatch(), (long) 0, OFFlowMod.OFPFC_DELETE, (short) 0,
                (short) 0, (short) 0, -1, (short) 7 /* out_port */,
                false, false, false, null, (long) 0);
        assertTrue(controllerStub.deletedFlows.contains(expectedFlow1));
        OFMatch port7Match = new MidoMatch();
        port7Match.setInputPort((short) 7);
        MockControllerStub.Flow expectedFlow2 = new MockControllerStub.Flow(
                port7Match, (long) 0, OFFlowMod.OFPFC_DELETE, (short) 0,
                (short) 0, (short) 0, -1, OFPort.OFPP_NONE.getValue(),
                false, false, false, null, (long) 0);
        assertTrue(controllerStub.deletedFlows.contains(expectedFlow2));

        // Send more packets.  They should make drop rules.
        controllerStub.addedFlows.clear();
        controller.onPacketIn(14, 13, (short)2, packet26.serialize());
        controller.onPacketIn(14, 13, (short)2, packet27.serialize());

        assertEquals(2, controllerStub.addedFlows.size());
        assertArrayEquals(new OFAction[] { },
                          controllerStub.addedFlows.get(0).actions.toArray());
        assertArrayEquals(new OFAction[] { },
                          controllerStub.addedFlows.get(1).actions.toArray());
    }

    @Test
    public void testFlowCountKeyHandling() {
        assertEquals(0, bridge.flowCount.size());
        MAC mac1a = MAC.fromString("00:00:00:00:01:0A");
        MAC mac1b = MAC.fromString("00:00:00:00:01:0A");
        MAC mac2 = MAC.fromString("00:00:00:00:02:00");
        assertEquals(mac1a, mac1b);
        assertNotSame(mac1a, mac1b);
        assertThat(mac1a, equalTo(mac1b));
        assertThat(mac1a, not(equalTo(mac2)));

        controller.onPacketIn(12, 13, (short)1, packet20.serialize());
        assertEquals(1, bridge.flowCount.size());
        Object key = bridge.flowCount.keySet().toArray()[0];
        assertTrue(bridge.flowCount.containsKey(key));

        // Add a flow with the same MAC, different port.
        controller.onPacketIn(12, 13, (short)2, packet23.serialize());
        // Add a flow with a different MAC, same port.
        controller.onPacketIn(12, 13, (short)1, packet15.serialize());
        assertEquals(3, bridge.flowCount.size());

        assertEquals(key, key);
        assertThat(key, not(equalTo(null)));
        assertThat(key, not(equalTo((Object)mac2)));
        assertFalse(key.equals(null));
        assertFalse(key.equals(mac2));

        Object[] macports = bridge.flowCount.keySet().toArray();
        assertThat(macports[0], not(equalTo(macports[1])));
        assertThat(macports[0], not(equalTo(macports[2])));
        assertThat(macports[1], not(equalTo(macports[2])));
    }

    @Test
    public void testOnFlowRemoved() {
        assertEquals(0, bridge.flowCount.size());
        controller.onPacketIn(12, 13, (short)1, packet20.serialize());
        assertEquals(1, bridge.flowCount.size());
        Object key = bridge.flowCount.keySet().toArray()[0];
        assertEquals(new Integer(1), bridge.flowCount.get(key));
        assertThat(flowmatch20.getWildcards() & OFMatch.OFPFW_IN_PORT,
                   not(equalTo(0)));
        controller.onFlowRemoved(flowmatch20, 13, (short)1000,
                                 OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT,
                                 timeout_ms/1000, 0, (short)(timeout_ms/1000),
                                 123, 456);
        assertEquals(new Integer(1), bridge.flowCount.get(key));
        MidoMatch match = flowmatch20.clone();
        match.setInputPort((short)1);
        match.setWildcards(match.getWildcards() | OFMatch.OFPFW_DL_SRC);
        controller.onFlowRemoved(match, 13, (short)1000,
                                 OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT,
                                 timeout_ms/1000, 0, (short)(timeout_ms/1000),
                                 123, 456);
        assertEquals(new Integer(1), bridge.flowCount.get(key));
        match.setDataLayerSource(macList[3]);
        controller.onFlowRemoved(match, 13, (short)1000,
                                 OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT,
                                 timeout_ms/1000, 0, (short)(timeout_ms/1000),
                                 123, 456);
        assertEquals(new Integer(1), bridge.flowCount.get(key));
        match.setDataLayerSource(macList[2]);
        match.setInputPort((short)30);
        controller.onFlowRemoved(match, 13, (short)1000,
                                 OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT,
                                 timeout_ms/1000, 0, (short)(timeout_ms/1000),
                                 123, 456);
        assertEquals(new Integer(1), bridge.flowCount.get(key));
        match.setInputPort((short)1);
        controller.onFlowRemoved(match, 13, (short)1000,
                                 OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT,
                                 timeout_ms/1000, 0, (short)(timeout_ms/1000),
                                 123, 456);
        assertNull(bridge.flowCount.get(key));
    }
}
