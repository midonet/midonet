// Copyright 2011 Midokura Inc.

package com.midokura.midolman;

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.OFPhysicalPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MacPortMap;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.util.Net;
import com.midokura.midolman.util.MAC;


public class TestBridgeController {
    Logger log = LoggerFactory.getLogger(TestBridgeController.class);

    private BridgeController controller;

    private Directory portLocDir, macPortDir;
    private PortToIntNwAddrMap portLocMap;
    private MacPortMap macPortMap;
    private MockOpenvSwitchDatabaseConnection ovsdb;
    private InetAddress publicIp;
    int dp_id = 43;
    MockControllerStub controllerStub;
    UUID portUuids[];
    MockReactor reactor;
    final int timeout_ms = 40*1000;

    public static final OFAction OUTPUT_ALL_ACTION = 
                new OFActionOutput(OFPort.OFPP_ALL.getValue(), (short)0);
    public static final OFAction OUTPUT_FLOOD_ACTION = 
                new OFActionOutput(OFPort.OFPP_FLOOD.getValue(), (short)0);

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
        packet.setDestinationMACAddress(dstMac.address);
        packet.setSourceMACAddress(srcMac.address);
        packet.setEtherType(IPv4.ETHERTYPE);
        return packet;
    }

    static MidoMatch makeFlowMatch(MAC srcMac, MAC dstMac) {
        MidoMatch match = new MidoMatch();
        match.setDataLayerDestination(dstMac.address);
        match.setDataLayerSource(srcMac.address);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setInputPort((short)1);
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
        match.setNetworkTypeOfService((byte)0);
        return match;
    }

    @Before
    public void setUp() throws java.lang.Exception {
        ovsdb = new MockOpenvSwitchDatabaseConnection();
        publicIp = InetAddress.getByAddress(
                       new byte[] { (byte)192, (byte)168, (byte)1, (byte)50 });

        // 'util_setup_controller_test':
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

        // Configuration for the Manager.
        String configString = 
            "[midolman]\n" +
            "midolman_root_key: /midolman\n" +
            "[bridge]\n" +
            "mac_port_mapping_expire_millis: 40000\n" +
            "[openflow]\n" +
            "flow_expire_millis: 300000\n" +
            "flow_idle_expire_millis: 60000\n" +
            "public_ip_address: 192.168.1.50\n" +
            "use_flow_wildcards: true\n" +
            "[openvswitch]\n" +
            "openvswitchdb_url: some://ovsdb.url/\n";
        // Populate hierarchicalConfiguration with configString.
        // To do this, we write it to a File, then pass that as the
        // constructor's argument.
        File confFile = File.createTempFile("bridge_test", "conf");
        confFile.deleteOnExit();
        FileOutputStream fos = new FileOutputStream(confFile);
        fos.write(configString.getBytes());
        fos.close();
        HierarchicalConfiguration hierarchicalConfiguration = 
                new HierarchicalINIConfiguration(confFile);
        SubnodeConfiguration midoConfig = 
            hierarchicalConfiguration.configurationAt("midolman");

        // Set up the (mock) ZooKeeper directories.
        MockDirectory zkDir = new MockDirectory();
        String midoDirName = zkDir.add(
            midoConfig.getString("midolman_root_key", "/zk_root"), null,
            CreateMode.PERSISTENT);
        Directory midoDir = zkDir.getSubDirectory(midoDirName);
        midoDir.add(midoConfig.getString("bridges_subdirectory", "/bridges"),
                    null, CreateMode.PERSISTENT);
        midoDir.add(midoConfig.getString("mac_port_subdirectory", "/mac_port"),
                    null, CreateMode.PERSISTENT);
        midoDir.add(midoConfig.getString("port_locations_subdirectory",
                                         "/port_locs"),
                    null, CreateMode.PERSISTENT);
        midoDir.add(midoConfig.getString("ports_subdirectory", "/ports"), null,
                    CreateMode.PERSISTENT);

        portLocDir = midoDir.getSubDirectory(
                midoConfig.getString("port_locations_subdirectory",
                                     "/port_locs"));
        portLocMap = new PortToIntNwAddrMap(portLocDir);
        macPortDir = midoDir.getSubDirectory(
                midoConfig.getString("mac_port_subdirectory", "/mac_port"));
        macPortMap = new MacPortMap(macPortDir);
        
        reactor = new MockReactor();
        
        // At this point in the python, we would create the controllerManager.
        // But we're not using a controllerManager in the Java tests.
        // ControllerTrampoline controllerManager = new ControllerTrampoline(
        //         hierarchicalConfiguration, ovsdb, zkDir, reactor);

        controllerStub = new MockControllerStub();

        UUID bridgeUUID = UUID.randomUUID();

        // The Python had at this point Manager.add_bridge() and
        // Manager.add_bridge_port() for each port, but we're not using
        // the controllerManager.

        controller = new BridgeController(
                /* datapathId */                dp_id, 
                /* switchUuid */                bridgeUUID,
                /* greKey */                    0xe1234,
                /* port_loc_map */              portLocMap,
                /* mac_port_map */              macPortMap,
                /* flowExpireMillis */          300*1000,
                /* idleFlowExpireMillis */      60*1000,
                /* publicIp */                  publicIp,
                /* macPortTimeoutMillis */      timeout_ms,
                /* ovsdb */                     ovsdb,
                /* reactor */                   reactor,
                /* externalIdKey */             "midonet");
        controller.setControllerStub(controllerStub);

        // Insert ports 3..8 into portLocMap and macPortMap.
        for (int i = 3; i < 8; i++) {
            portLocMap.put(portUuids[i], 
                           Net.convertStringAddressToInt(peerStrList[i]));
            macPortMap.put(macList[i], portUuids[i]);
            log.info("Adding map MAC {} -> port {} -> loc {}",
                     new Object[] { macList[i], portUuids[i],
                                    peerStrList[i] });
        }

        portLocMap.start();
        macPortMap.start();

        // Populate phyPorts and add to controller.
        for (int i = 0; i < 8; i++) {
            phyPorts[i].setPortNumber((short)i);
            phyPorts[i].setHardwareAddress(macList[i].address);
            // First three ports are local.  The rest are tunneled.
            phyPorts[i].setName(i < 3 ? "port" + Integer.toString(i)
                                      : controller.makeGREPortName(
                                            Net.convertStringAddressToInt(
                                                    peerStrList[i])));
            controller.onPortStatus(phyPorts[i], OFPortReason.OFPPR_ADD);
        }
    }

    void checkInstalledFlow(OFMatch expectedMatch, int idleTimeout,
                            int hardTimeoutMin, int hardTimeoutMax,
                            int priority, OFAction[] actions) {
        assertEquals(1, controllerStub.addedFlows.size());
        MockControllerStub.Flow flow = controllerStub.addedFlows.get(0);
        assertEquals(expectedMatch, flow.match);
        assertEquals(idleTimeout, flow.idleTimeoutSecs);
        assertTrue(hardTimeoutMin <= flow.hardTimeoutSecs);  
        assertTrue(hardTimeoutMax >= flow.hardTimeoutSecs);  
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
    public void testPacketInWithNovelMac() {
        MidoMatch expectedMatch = flowmatch01.clone();
        short inputPort = 0;
        expectedMatch.setInputPort(inputPort);
        OFAction expectedActions[] = { OUTPUT_ALL_ACTION };
        controller.onPacketIn(14, 13, inputPort, packet01.serialize());
        checkInstalledFlow(expectedMatch, 60, 300, 300, 1000, expectedActions);
        checkSentPacket(14, (short)-1, expectedActions, new byte[] {});
    }

    @Test
    public void testMulticastLocalInPort() {
        final Ethernet packet = packet0MC;
        short inPortNum = 0;
        MidoMatch expectedMatch = flowmatch0MC.clone();
        expectedMatch.setInputPort(inPortNum);
        OFAction[] expectActions = { OUTPUT_ALL_ACTION };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectedMatch, 60, 300, 300, 1000, expectActions);
        checkSentPacket(14, (short)-1, expectActions, new byte[] {});
    }

    @Test
    public void testMulticastTunnelInPort() {
        final Ethernet packet = packet0MC;
        short inPortNum = 5;
        MidoMatch expectedMatch = flowmatch0MC.clone();
        expectedMatch.setInputPort(inPortNum);
        OFAction[] expectActions = { OUTPUT_FLOOD_ACTION };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectedMatch, 60, 300, 300, 1000, expectActions);
        checkSentPacket(14, (short)-1, expectActions, new byte[] {});
    }

    @Test
    public void testRemoteMACWithTunnel() {
        final Ethernet packet = packet13;
        short inPortNum = 1;
        short outPortNum = 3;
        MidoMatch expectMatch = flowmatch13.clone();
        expectMatch.setInputPort(inPortNum);
        OFAction[] expectAction = { new OFActionOutput(outPortNum, (short)0) };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectMatch, 60, 300, 300, 1000, expectAction);
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
    }

    @Test
    public void testRemoteMACNoTunnel() {
        final Ethernet packet = packet13;
        short inPortNum = 1;
        short outPortNum = 3;
        MidoMatch expectMatch = flowmatch13.clone();
        expectMatch.setInputPort(inPortNum);
        OFAction[] expectAction = { OUTPUT_ALL_ACTION };
        controller.onPortStatus(phyPorts[3], OFPortReason.OFPPR_DELETE);
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectMatch, 60, 300, 300, 1000, expectAction);
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
    }

    @Test
    public void testRemoteMACTunnelIn() {
        short inPortNum = 4;
        final Ethernet packet = packet13;
        MidoMatch expectMatch = flowmatch13.clone();
        expectMatch.setInputPort(inPortNum);
        OFAction[] expectAction = { };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        // Verify drop rule & no packet sent.
        checkInstalledFlow(expectMatch, 60, 300, 300, 1000, expectAction);
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
                { new OFActionOutput((short)3, (short)0) },
                { new OFActionOutput((short)3, (short)0) },
                { new OFActionOutput((short)4, (short)0) },
        };

        assertEquals(3, controllerStub.addedFlows.size());
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(expectedActions[i], 
                              controllerStub.addedFlows.get(i)
                                            .actions.toArray());
        }

        assertEquals(oldDelCount+4, controllerStub.deletedFlows.size());
        MidoMatch expectedDeletes[] = {
                new MidoMatch(), new MidoMatch(),
                new MidoMatch(), new MidoMatch() };
        expectedDeletes[0].setDataLayerSource(macList[0].address);
        expectedDeletes[1].setDataLayerDestination(macList[0].address);
        expectedDeletes[2].setDataLayerSource(macList[1].address);
        expectedDeletes[3].setDataLayerDestination(macList[1].address);
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
        expectedMatch.setDataLayerDestination(macList[3].address);
        assertEquals(expectedMatch,
                     controllerStub.deletedFlows.get(oldDelCount).match);
        log.info("Removing port {} from portLocMap", portUuids[4]);
        portLocMap.remove(portUuids[4]);
        assertEquals(oldDelCount+2, controllerStub.deletedFlows.size());
        expectedMatch.setDataLayerDestination(macList[4].address);
        assertEquals(expectedMatch,
                     controllerStub.deletedFlows.get(oldDelCount+1).match);
    }

    @Test
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
        checkInstalledFlow(expectMatch, 60, 300, 300, 1000, expectAction);
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});

        assertEquals(oldDelCount+2, controllerStub.deletedFlows.size());
        MidoMatch expectSrcDelete = new MidoMatch();
        expectSrcDelete.setDataLayerSource(macList[1].address);
        MidoMatch expectDstDelete = new MidoMatch();
        expectDstDelete.setDataLayerDestination(macList[1].address);
        assertEquals(expectSrcDelete,
                     controllerStub.deletedFlows.get(oldDelCount).match);
        assertEquals(expectDstDelete,
                     controllerStub.deletedFlows.get(oldDelCount+1).match);
        oldDelCount += 2;

        // Move the port, check that the controller removed the flow.
        portLocMap.put(portUuids[3], Net.convertStringAddressToInt("1.2.3.4"));
        assertTrue(oldDelCount < controllerStub.deletedFlows.size());
        MidoMatch expectedMatch = new MidoMatch();
        expectedMatch.setDataLayerDestination(macList[3].address);
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
        OFAction[] expectAction = { OUTPUT_ALL_ACTION };
        controller.onPacketIn(14, 13, inPortNum, packet01.serialize());
        checkInstalledFlow(expectMatch, 60, 300, 300, 1000, expectAction);
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
        
        // Verify that MAC was learned.
        assertEquals(portUuids[inPortNum], macPortMap.get(mac));

        // Send packet to learned MAC.
        inPortNum = 1;
        outPortNum = 0;
        expectMatch = flowmatch10.clone();
        expectMatch.setInputPort(inPortNum);
        expectAction = new OFAction[] { 
                new OFActionOutput(outPortNum, (short)0) };
        controllerStub.addedFlows.clear();
        controllerStub.sentPackets.clear();
        controller.onPacketIn(14, 13, inPortNum, packet10.serialize());
        checkInstalledFlow(expectMatch, 60, 300, 300, 1000, expectAction);
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
        OFAction[] expectAction = { new OFActionOutput(outPortNum, (short)0) };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectMatch, 60, 300, 300, 1000, expectAction);
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
        
        // Remove the port->location mapping for the remote port and verify
        // that the new packets generate flood-to-all flow matches.
        portLocMap.remove(portUuids[outPortNum]);
        controllerStub.addedFlows.clear();
        controllerStub.sentPackets.clear();

        expectAction = new OFAction[] { OUTPUT_ALL_ACTION };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectMatch, 60, 300, 300, 1000, expectAction);
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
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
        OFAction[] expectAction = { OUTPUT_ALL_ACTION };
        controller.onPacketIn(14, 13, inPortNum, packet.serialize());
        checkInstalledFlow(expectMatch, 60, 300, 300, 1000, expectAction);
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});

        // Add the port->loc mapping, verify that the flow is removed.
        assertEquals(1, controllerStub.addedFlows.size());
        controllerStub.deletedFlows.clear();
        MidoMatch removeMatchDst = new MidoMatch();
        removeMatchDst.setDataLayerDestination(macList[outPortNum].address);
        portLocMap.put(portUuids[outPortNum], 
                       Net.convertStringAddressToInt(peerStrList[outPortNum]));
        assertTrue(controllerStub.deletedFlows.size() > 0);
        for (int i = 0; i < controllerStub.deletedFlows.size(); i++) {
            assertEquals(removeMatchDst, 
                         controllerStub.deletedFlows.get(i).match);
        }
    }

    @Test
    public void testBadlyMappedPortUuid() 
                throws KeeperException, InterruptedException {
        MAC srcMac = MAC.fromString("00:AA:AA:AA:AA:00");
        MAC dstMac = MAC.fromString("00:AA:AA:AA:AA:01");
        UUID dstUuid = UUID.fromString("251cbfb6-9ca1-4685-9320-c7203c4ffff2");
        macPortMap.put(dstMac, dstUuid);
        portLocMap.put(dstUuid, Net.convertInetAddressToInt(publicIp));
        Ethernet packet = makePacket(srcMac, dstMac);
        MidoMatch flowmatch = makeFlowMatch(srcMac, dstMac);
        
        // Send the packet from both local and tunnel ports.
        for (short inPortNum : new short[] { 2, 4 }) {
            controllerStub.addedFlows.clear();
            controllerStub.sentPackets.clear();
            MidoMatch expectMatch = flowmatch.clone();
            expectMatch.setInputPort(inPortNum);
            OFAction[] expectAction = {
                inPortNum == 2 ? OUTPUT_ALL_ACTION : OUTPUT_FLOOD_ACTION };
            controller.onPacketIn(14, 13, inPortNum, packet.serialize());
            checkInstalledFlow(expectMatch, 60, 300, 300, 1000, expectAction);
            checkSentPacket(14, (short)-1, expectAction, new byte[] {});
        }
    }

    @Test
    public void testFlowCount() {
        short inPortNum = 0;
        int numFlows = 3;
        for (int i = 0; i < numFlows; i++) {
            controller.onPacketIn(14, 13, inPortNum, packet04.serialize());
        }
        assertEquals(numFlows, controllerStub.addedFlows.size());

        assertEquals(portUuids[inPortNum], macPortMap.get(macList[inPortNum]));
        MidoMatch match = new MidoMatch();
        match.setInputPort(inPortNum);
        match.setDataLayerSource(macList[inPortNum].address);
        for (int i = 0; i < numFlows; i++) {
            reactor.incrementTime(timeout_ms, TimeUnit.MILLISECONDS);
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
    public void testMacChangesPort() {
        controller.onPacketIn(14, 13, (short)1, packet10.serialize());
        assertEquals(portUuids[1], macPortMap.get(macList[1]));        
        // MAC moves to port 2.
        controller.onPacketIn(14, 13, (short)2, packet10.serialize());
        assertEquals(portUuids[2], macPortMap.get(macList[1]));        
        MidoMatch match = new MidoMatch();
        match.setInputPort((short)1);
        match.setDataLayerSource(macList[1].address);
        controller.onFlowRemoved(match, 0, (short)1000, 
                        OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT, 
                        timeout_ms/1000, 0, (short)(timeout_ms/1000), 123, 456);
        reactor.incrementTime(timeout_ms+1, TimeUnit.MILLISECONDS);
        assertEquals(portUuids[2], macPortMap.get(macList[1]));        

        match.setInputPort((short)2);
        controller.onFlowRemoved(match, 0, (short)1000, 
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
        checkInstalledFlow(expectMatch, 60, 300, 300, 1000, expectAction);
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
        controller.onPacketIn(14, 13, (short)5, packet70.serialize());
        OFAction[] expectAction = { OUTPUT_FLOOD_ACTION };
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
        assertEquals(1, controllerStub.addedFlows.size());
        assertArrayEquals(expectAction, 
                          controllerStub.addedFlows.get(0).actions.toArray());

        // Send a packet[src=0 dst=1] to port 0.
        controllerStub.sentPackets.clear();     
        controllerStub.deletedFlows.clear();     
        expectAction = new OFAction[] { OUTPUT_ALL_ACTION };
        controller.onPacketIn(14, 13, (short)0, packet01.serialize());
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
        // New flow added, old flow removed.
        assertEquals(2, controllerStub.addedFlows.size());
        assertArrayEquals(expectAction, 
                          controllerStub.addedFlows.get(1).actions.toArray());
        MidoMatch expectMatch = new MidoMatch();
        expectMatch.setDataLayerDestination(macList[0].address);
        assertTrue(0 < controllerStub.deletedFlows.size());
        assertEquals(expectMatch,
                     controllerStub.deletedFlows.get(
                                controllerStub.deletedFlows.size()-1).match);

        // Send a packet[src=1 dst=0] to port 1.
        controllerStub.sentPackets.clear();
        controllerStub.deletedFlows.clear();    
        expectAction = new OFAction[] { 
                                new OFActionOutput((short)0, (short)0) };
        controller.onPacketIn(14, 13, (short)1, packet10.serialize());
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
        // New flow added, old flow removed.
        assertEquals(3, controllerStub.addedFlows.size());
        assertArrayEquals(expectAction, 
                          controllerStub.addedFlows.get(2).actions.toArray());
        expectMatch.setDataLayerDestination(macList[1].address);
        assertTrue(0 < controllerStub.deletedFlows.size());
        assertEquals(expectMatch,
                     controllerStub.deletedFlows.get(
                                controllerStub.deletedFlows.size()-1).match);
        
        // Send a packet[src=0 dst=4] to port 1.  (MAC 0 moves from port 0
        // to port 1.)
        controllerStub.sentPackets.clear();
        controllerStub.deletedFlows.clear();
        expectAction = new OFAction[] {
                                new OFActionOutput((short)4, (short)0) };
        controller.onPacketIn(14, 13, (short)1, packet04.serialize());
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
        // New flow added, invalidation of srcMAC = MAC 0 or dstMAC = MAC 0.
        assertEquals(4, controllerStub.addedFlows.size());
        assertArrayEquals(expectAction,
                          controllerStub.addedFlows.get(3).actions.toArray());
        assertEquals(2, controllerStub.deletedFlows.size());
        expectMatch.setDataLayerDestination(macList[0].address);
        assertEquals(expectMatch, controllerStub.deletedFlows.get(1).match);
        expectMatch = new MidoMatch();
        expectMatch.setDataLayerSource(macList[0].address);
        assertEquals(expectMatch, controllerStub.deletedFlows.get(0).match);

        // MAC 0 moves to remote port 5.
        controllerStub.deletedFlows.clear();
        macPortMap.put(macList[0], portUuids[5]);
        // Flows to or from MAC 0 invalidated.
        for (MockControllerStub.Flow flow : controllerStub.deletedFlows) {
            log.info("Deleted flow: {}", flow);
        }
        assertTrue(2 <= controllerStub.deletedFlows.size());
        assertEquals(expectMatch, controllerStub.deletedFlows.get(0).match);
        expectMatch = new MidoMatch();
        expectMatch.setDataLayerDestination(macList[0].address);
        assertEquals(expectMatch, controllerStub.deletedFlows.get(1).match);

        // Send a packet[src=1 dst=0] to port 1.
        controllerStub.sentPackets.clear();
        expectAction = new OFAction[] {
                                new OFActionOutput((short)5, (short)0) };
        controller.onPacketIn(14, 13, (short)1, packet10.serialize());
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
        // New flow to tunnel port 5 added.
        assertEquals(5, controllerStub.addedFlows.size());
        assertArrayEquals(expectAction,
                          controllerStub.addedFlows.get(4).actions.toArray());
        
        // Send a packet[src=0 dst=1] to port 5.
        controllerStub.sentPackets.clear();
        expectAction = new OFAction[] {
                                new OFActionOutput((short)1, (short)0) };
        controller.onPacketIn(14, 13, (short)5, packet01.serialize());
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
        assertEquals(6, controllerStub.addedFlows.size());
        assertArrayEquals(expectAction,
                          controllerStub.addedFlows.get(5).actions.toArray());
        
        // Send a packet[src=1 dst=3] to port 1.
        controllerStub.sentPackets.clear();
        expectAction = new OFAction[] {
                                new OFActionOutput((short)3, (short)0) };
        controller.onPacketIn(14, 13, (short)1, packet13.serialize());
        checkSentPacket(14, (short)-1, expectAction, new byte[] {});
        assertEquals(7, controllerStub.addedFlows.size());
        assertArrayEquals(expectAction,
                          controllerStub.addedFlows.get(6).actions.toArray());

        // Remove MAC 0 from macPortMap.
        controllerStub.deletedFlows.clear();
        macPortMap.remove(macList[0]);
        // Only flows with dstMAC = MAC 0 should be invalidated.  Flows with
        // srcMAC = MAC 0 should still be valid.
        assertEquals(1, controllerStub.deletedFlows.size());
        expectMatch = new MidoMatch();
        expectMatch.setDataLayerDestination(macList[0].address);
        assertEquals(expectMatch, controllerStub.deletedFlows.get(0).match);
    }

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
        portLocMap.put(portUuids[5], 
                       Net.convertStringAddressToInt(peerStrList[3]));
        // Flows to MAC 5 should have been invalidated.
        MidoMatch expectMatch = new MidoMatch();
        expectMatch.setDataLayerDestination(macList[5].address);
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
        expectMatch.setDataLayerDestination(macList[5].address);
        assertTrue(flowListContainsMatch(controllerStub.deletedFlows,
                                         expectMatch));

        // Now packet25 should go to ALL.
        controller.onPacketIn(14, 13, (short)2, packet25.serialize());
        assertEquals(6, controllerStub.addedFlows.size());
        assertArrayEquals(new OFAction[] { OUTPUT_ALL_ACTION },
                          controllerStub.addedFlows.get(5).actions.toArray());


    }

    boolean flowListContainsMatch(List<MockControllerStub.Flow> flowList,
                                  OFMatch match) {
        for (MockControllerStub.Flow flow : flowList)
            if (flow.match.equals(match))
                return true;

        return false;
    }
}
