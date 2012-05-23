/* Copyright 2011 Midokura Inc. */

package com.midokura.midolman;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.OFPhysicalPort;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.LLDP;
import com.midokura.midolman.packets.LLDPTLV;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.MalformedPacketException;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.TCP;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.util.Net;


class AbstractControllerTester extends AbstractController {
    public List<UUID> virtualPortsAdded;
    public List<UUID> virtualPortsRemoved;
    public List<IntIPv4> tunnelPortsAdded;
    public List<IntIPv4> tunnelPortsRemoved;
    public int numClearCalls;
    short flowExpireSeconds;
    short idleFlowExpireSeconds;
    OFAction[] flowActions = {};

    public Logger log = LoggerFactory.getLogger(AbstractControllerTester.class);

    AbstractControllerTester(
            OpenvSwitchDatabaseConnection ovsdb,
            short flowExpireSeconds,
            long idleFlowExpireMillis,
            IntIPv4 internalIp,
            Directory dir,
            String basePath,
            UUID vrnID) throws StateAccessException {
        super(dir, basePath, ovsdb, internalIp, "midonet", vrnID, false);
        virtualPortsAdded = new ArrayList<UUID>();
        virtualPortsRemoved = new ArrayList<UUID>();
        tunnelPortsAdded = new ArrayList<IntIPv4>();
        tunnelPortsRemoved = new ArrayList<IntIPv4>();
        numClearCalls = 0;
        this.flowExpireSeconds = flowExpireSeconds;
        this.idleFlowExpireSeconds = (short)(idleFlowExpireMillis/1000);
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort,
                           byte[] data, long matchingTunnelId) {
        Ethernet frame = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(data, 0, data.length);
        try {
            frame.deserialize(bb);
        } catch (MalformedPacketException e) {
            // Dropping malformed packets
            log.error("Dropping malformed packet: {}", e.getMessage());
            freeBuffer(bufferId);
            return;
        }
        OFMatch match = createMatchFromPacket(frame, inPort);
        addFlowAndPacketOut(match, 1040, idleFlowExpireSeconds,
                            flowExpireSeconds, (short)1000, bufferId, true,
                            false, false, flowActions, inPort, data);
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort,
                           byte[] data) {
        onPacketIn(bufferId, totalLen, inPort, data, 0);
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie,
            short priority, OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount, long matchingTunnelId) { }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) {
        onFlowRemoved(match, cookie, priority, reason, durationSeconds,
                durationNanoseconds, idleTimeout, packetCount, byteCount, 0);
    }

    public void clear() {
        virtualPortsAdded.clear();
        virtualPortsRemoved.clear();
        tunnelPortsAdded.clear();
        tunnelPortsRemoved.clear();
        numClearCalls++;
    }

    @Override
    protected void portMoved(UUID portUuid, IntIPv4 oldAddr, IntIPv4 newAddr) {
        // Do nothing.
    }

    public void setFeatures(OFFeaturesReply features) {
        ((MockControllerStub) controllerStub).setFeatures(features);
    }

    @Override
    public String makeGREPortName(IntIPv4 a) {
        return super.makeGREPortName(a);
    }

    @Override
    public IntIPv4 peerIpOfGrePortName(String s) {
        return super.peerIpOfGrePortName(s);
    }

    @Override
    protected void addVirtualPort(int num, String name, MAC addr, UUID vId) {
        virtualPortsAdded.add(vId);
    }

    @Override
    protected void deleteVirtualPort(int num, UUID vId) {
        virtualPortsRemoved.add(vId);
    }

    @Override
    protected void addServicePort(int num, String name, UUID vId) {}

    @Override
    protected void deleteServicePort(int num, String name, UUID vId) {}

    @Override
    protected void initServicePorts(long datapathId) {}

    @Override
    protected void addTunnelPort(int num, IntIPv4 peerIP) {
        tunnelPortsAdded.add(peerIP);
    }

    @Override
    protected void deleteTunnelPort(int num, IntIPv4 peerIP) {
        tunnelPortsRemoved.add(peerIP);
    }
}


public class TestAbstractController {

    private AbstractControllerTester controller;

    private OFPhysicalPort port1;
    private OFPhysicalPort port2;
    private OFPhysicalPort port3;
    private IntIPv4 localIp;
    private UUID port1uuid;
    private IntIPv4 port2peer;
    private UUID port3uuid;
    private int dp_id;
    private UUID vrnId;
    private MockOpenvSwitchDatabaseConnection ovsdb;
    private PortToIntNwAddrMap portLocMap;
    private Directory portLocationDirectory;
    private MockControllerStub controllerStub = new MockControllerStub();

    public Logger log = LoggerFactory.getLogger(TestAbstractController.class);

    @Before
    public void setUp() throws StateAccessException, KeeperException,
                               InterruptedException {
        dp_id = 43;
        vrnId = UUID.randomUUID();
        ovsdb = new MockOpenvSwitchDatabaseConnection();
        ovsdb.setDatapathExternalId(dp_id, "midonet", vrnId.toString());

        String basePath = "/midonet";
        MockDirectory mockDir = new MockDirectory();
        mockDir.add(basePath, null, CreateMode.PERSISTENT);
        Setup.createZkDirectoryStructure(mockDir, basePath);
        ZkPathManager pathMgr = new ZkPathManager(basePath);
        portLocationDirectory = mockDir.getSubDirectory(
                pathMgr.getVRNPortLocationsPath());
        portLocMap = new PortToIntNwAddrMap(portLocationDirectory);
        portLocMap.start();

        localIp = IntIPv4.fromString("192.168.1.50");
        controller = new AbstractControllerTester(
                             ovsdb /* ovsdb */,
                             (short)300 /* flowExpireSeconds */,
                             60 * 1000 /* idleFlowExpireMillis */,
                             localIp /* internalIp */,
                             mockDir /* mock Directory */,
                             basePath /* ZK root path */,
                             vrnId /* VRN ID */);

        controller.setControllerStub(controllerStub);
        controller.datapathId = dp_id;
        controller.portLocMap.start();

        port1 = new OFPhysicalPort();
        port1.setPortNumber((short) 37);
        port1.setHardwareAddress(new byte[]{10, 12, 13, 14, 15, 37});
        port1uuid = UUID.randomUUID();
        ovsdb.setPortExternalId(dp_id, 37, "midonet", port1uuid.toString());

        port2 = new OFPhysicalPort();
        port2.setPortNumber((short) 47);
        port2.setHardwareAddress(new byte[] { 10, 12, 13, 14, 15, 47 });
        port2.setName("tn0a001122");
        port2peer = new IntIPv4(0x0a001122);

        port3 = new OFPhysicalPort();
        port3.setPortNumber((short) 57);
        port3.setHardwareAddress(new byte[] { 10, 12, 13, 14, 15, 57 });
        port3.setConfig(AbstractController.portDownFlag);
        port3uuid = UUID.randomUUID();
        ovsdb.setPortExternalId(dp_id, 57, "midonet", port3uuid.toString());

        controller.onPortStatus(port1, OFPortReason.OFPPR_ADD);
        controller.onPortStatus(port2, OFPortReason.OFPPR_ADD);
        controller.onPortStatus(port3, OFPortReason.OFPPR_ADD);
    }

    @Test
    public void testPortMap() {
        assertEquals(new Integer(37),
                controller.portUuidToNumberMap.get(port1uuid));
        assertEquals(port1uuid, controller.portNumToUuid.get(37));
        // port1uuid should be in the portLocMap.
        assertEquals(localIp, portLocMap.get(port1uuid));
        // Port 57 is virtual but its link is down.
        assertNull(controller.portUuidToNumberMap.get(port3uuid));
        assertNull(controller.portNumToUuid.get(57));
        assertNull(portLocMap.get(port3uuid));
        // Port 47 is a tunnel
        assertNull(controller.portNumToUuid.get(47));
        assertNull(controller.portNumToUuid.get(57));
        // Only port 47 is a tunnel.
        assertFalse(controller.isTunnelPortNum(37));
        assertFalse(controller.isTunnelPortNum(57));
        assertTrue(controller.isTunnelPortNum(47));
        assertNull(controller.peerOfTunnelPortNum(37));
        assertNull(controller.peerOfTunnelPortNum(57));
        assertEquals(port2peer, controller.peerOfTunnelPortNum(47));
    }

    @Test
    public void testConnectionMade() {
        OFFeaturesReply features = new OFFeaturesReply();
        ArrayList<OFPhysicalPort> portList = new ArrayList<OFPhysicalPort>();
        portList.add(port1);    // Regular port, gets recorded.
        portList.add(port2);    // Tunnel port, gets deleted.
        features.setPorts(portList);
        features.setDatapathId(dp_id);
        controller.setFeatures(features);
        controller.onConnectionLost();
        assertArrayEquals(new OFPhysicalPort[]{},
                controller.virtualPortsAdded.toArray());
        assertArrayEquals(new String[]{},
                ovsdb.deletedPorts.toArray());
        MockControllerStub stub =
                (MockControllerStub) controller.controllerStub;
        assertEquals(0, stub.deletedFlows.size());
        controller.onConnectionMade();
        assertArrayEquals(new UUID[]{port1uuid},
                controller.virtualPortsAdded.toArray());
        assertArrayEquals(new String[]{port2.getName()},
                ovsdb.deletedPorts.toArray());
        assertEquals(1, stub.deletedFlows.size());
        assertEquals(OFMatch.OFPFW_ALL,
                     stub.deletedFlows.get(0).match.getWildcards());
    }

    @Test
    public void testClearAdd() {
        assertArrayEquals(new UUID[] { port1uuid },
                controller.virtualPortsAdded.toArray());
        assertArrayEquals(new IntIPv4[] { port2peer },
                controller.tunnelPortsAdded.toArray());
        assertEquals(0, controller.numClearCalls);
        controller.onConnectionLost();
        assertEquals(1, controller.numClearCalls);
        assertArrayEquals(new OFPhysicalPort[] { },
                controller.virtualPortsAdded.toArray());
        assertArrayEquals(new IntIPv4[] { },
                controller.tunnelPortsAdded.toArray());
        controller.onPortStatus(port1, OFPortReason.OFPPR_ADD);
        controller.onPortStatus(port2, OFPortReason.OFPPR_ADD);
        assertArrayEquals(new UUID[]{port1uuid},
                controller.virtualPortsAdded.toArray());
        assertArrayEquals(new IntIPv4[]{port2peer},
                controller.tunnelPortsAdded.toArray());
        assertEquals(port1uuid, controller.portNumToUuid.get(37));
        assertNull(controller.portNumToUuid.get(47));
        assertNull(controller.peerOfTunnelPortNum(37));
        assertEquals("10.0.17.34",
                     controller.peerOfTunnelPortNum(47).toString());
    }

    @Test
    public void testBringPortUp() {
        assertArrayEquals(new UUID[] { port1uuid },
                controller.virtualPortsAdded.toArray());
        assertArrayEquals(new IntIPv4[] { port2peer },
                controller.tunnelPortsAdded.toArray());
        assertEquals(localIp, portLocMap.get(port1uuid));
        assertNull(portLocMap.get(port3uuid));
        port3.setConfig(0);
        controller.onPortStatus(port3, OFPortReason.OFPPR_MODIFY);
        assertArrayEquals(new UUID[]{port1uuid, port3uuid},
                controller.virtualPortsAdded.toArray());
        assertArrayEquals(new IntIPv4[]{port2peer},
                controller.tunnelPortsAdded.toArray());
        assertEquals(localIp, portLocMap.get(port1uuid));
        assertEquals(localIp, portLocMap.get(port3uuid));
    }

    @Test
    public void testModifyPort() {
        port2.setName("tn0a001123");
        controller.onPortStatus(port2, OFPortReason.OFPPR_MODIFY);
        assertNull(controller.portNumToUuid.get(47));
        assertEquals("10.0.17.35",
                controller.peerOfTunnelPortNum(47).toString());
    }

    @Test
    public void testModifyDownPort() {
        assertArrayEquals(new UUID[] { },
                controller.virtualPortsRemoved.toArray());
        assertArrayEquals(new IntIPv4[] { },
                controller.tunnelPortsRemoved.toArray());
        assertArrayEquals(new UUID[] { port1uuid },
                controller.virtualPortsAdded.toArray());
        assertArrayEquals(new IntIPv4[] { port2peer },
                controller.tunnelPortsAdded.toArray());
        UUID port3newUuid = UUID.randomUUID();
        ovsdb.setPortExternalId(dp_id, 57, "midonet", port3newUuid.toString());
        controller.onPortStatus(port3, OFPortReason.OFPPR_MODIFY);
        assertNull(controller.portNumToUuid.get(57));
        assertNull(controller.peerOfTunnelPortNum(57));
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.virtualPortsRemoved.toArray());
        assertArrayEquals(new UUID[] { port1uuid },
                controller.virtualPortsAdded.toArray());
        assertArrayEquals(new IntIPv4[] { port2peer },
                controller.tunnelPortsAdded.toArray());
    }

    @Test
    public void testDeletePort() {
        MockControllerStub stub =
                (MockControllerStub) controller.controllerStub;
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.virtualPortsRemoved.toArray());
        assertTrue(controller.portNumToUuid.containsKey(37));
        assertEquals(localIp, portLocMap.get(port1uuid));
        assertNull(controller.peerOfTunnelPortNum(37));
        controller.onPortStatus(port1, OFPortReason.OFPPR_DELETE);
        assertArrayEquals(new UUID[] { port1uuid },
                          controller.virtualPortsRemoved.toArray());
        assertFalse(controller.portNumToUuid.containsKey(37));
        assertNull(portLocMap.get(port1uuid));
        // There should be 2 delete flow_mods, one with 37 as input port,
        // the other with 37 as output port.
        assertEquals(2, stub.deletedFlows.size());
        assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_IN_PORT,
                     stub.deletedFlows.get(0).match.getWildcards());
        assertEquals(37, stub.deletedFlows.get(0).match.getInputPort());
        assertEquals(OFMatch.OFPFW_ALL,
                     stub.deletedFlows.get(1).match.getWildcards());
        assertEquals(37, stub.deletedFlows.get(1).outPort);
        assertEquals(port2peer, controller.peerOfTunnelPortNum(47));
        controller.onPortStatus(port2, OFPortReason.OFPPR_DELETE);
        assertArrayEquals(new IntIPv4[] { port2peer },
                          controller.tunnelPortsRemoved.toArray());
        assertFalse(controller.portNumToUuid.containsKey(47));
        assertNull(controller.peerOfTunnelPortNum(47));
        // There should be 2 more delete flow_mods, one with 47 as input port,
        // the other with 47 as output port.
        assertEquals(4, stub.deletedFlows.size());
        assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_IN_PORT,
                     stub.deletedFlows.get(2).match.getWildcards());
        assertEquals(47, stub.deletedFlows.get(2).match.getInputPort());
        assertEquals(OFMatch.OFPFW_ALL,
                     stub.deletedFlows.get(3).match.getWildcards());
        assertEquals(47, stub.deletedFlows.get(3).outPort);
    }

    @Test
    public void testBringPortDown() {
        assertArrayEquals(new UUID[] { },
                          controller.virtualPortsRemoved.toArray());
        assertTrue(controller.portNumToUuid.containsKey(37));
        assertEquals(localIp, portLocMap.get(port1uuid));
        port1.setConfig(AbstractController.portDownFlag);
        controller.onPortStatus(port1, OFPortReason.OFPPR_MODIFY);
                assertArrayEquals(new UUID[] { port1uuid },
                          controller.virtualPortsRemoved.toArray());
        assertFalse(controller.portNumToUuid.containsKey(37));
        assertNull(portLocMap.get(port1uuid));
        assertFalse(controller.portNumToUuid.containsKey(47));
        assertEquals(port2peer, controller.peerOfTunnelPortNum(47));
    }

    @Test
    public void testDeleteDownPort() {
        assertArrayEquals(new UUID[] { },
                          controller.virtualPortsRemoved.toArray());
        controller.onPortStatus(port3, OFPortReason.OFPPR_DELETE);
        assertArrayEquals(new UUID[]{},
                controller.virtualPortsRemoved.toArray());
    }

    @Test
    public void testMakeGREPortName() {
        assertEquals("tnff0011aa",
                     controller.makeGREPortName(new IntIPv4(0xff0011aa)));
    }

    @Test
    public void testPeerIpOfGrePortName() {
        assertEquals(0xff0011aa,
                controller.peerIpOfGrePortName("tnff0011aa").address);
    }

    @Test
    public void testPeerIpToTunnelPortNum() {
        IntIPv4 peerIP = IntIPv4.fromString("192.168.1.53");
        String grePortName = controller.makeGREPortName(peerIP);
        assertEquals(peerIP, controller.peerIpOfGrePortName(grePortName));

        OFPhysicalPort port = new OFPhysicalPort();
        port.setPortNumber((short) 54);
        port.setHardwareAddress(new byte[] { 10, 12, 13, 14, 15, 54 });
        port.setName(grePortName);
        controller.onPortStatus(port, OFPortReason.OFPPR_ADD);
        log.debug("peerIP: {}", peerIP);
        assertEquals(new Integer(54),
                     controller.tunnelPortNumOfPeer(peerIP));
    }

    @Test
    public void testPortLocMapListener()
            throws KeeperException, InterruptedException {
        ovsdb.addedGrePorts.clear();
        UUID portUuid = UUID.randomUUID();
        String path1 = "/"+portUuid.toString()+",255.0.17.170,";
        String path2 = "/"+portUuid.toString()+",255.0.17.172,";

        // Port comes up.  Verify tunnel made.
        String fullpath1 = portLocationDirectory.add(path1, null,
                                       CreateMode.PERSISTENT_SEQUENTIAL);
        assertEquals(1, ovsdb.addedGrePorts.size());
        assertTrue((new MockOpenvSwitchDatabaseConnection.GrePort(
                            "43", "tnff0011aa", "255.0.17.170")).equals(
                   ovsdb.addedGrePorts.get(0)));
        assertEquals(0xff0011aa, portLocMap.get(portUuid).address);
        assertEquals(0, ovsdb.deletedPorts.size());

        // Port moves.  Verify old tunnel rm'd, new tunnel made.
        String fullpath2 = portLocationDirectory.add(path2, null,
                                       CreateMode.PERSISTENT_SEQUENTIAL);
        portLocationDirectory.delete(fullpath1);
        assertEquals(2, ovsdb.addedGrePorts.size());
        assertTrue((new MockOpenvSwitchDatabaseConnection.GrePort(
                            "43", "tnff0011ac", "255.0.17.172")).equals(
                   ovsdb.addedGrePorts.get(1)));
        assertEquals(0xff0011ac, portLocMap.get(portUuid).address);
        assertEquals(1, ovsdb.deletedPorts.size());
        assertEquals("tnff0011aa", ovsdb.deletedPorts.get(0));

        // Port doesn't move.  Verify tunnel not rm'd.
        String path3 = portLocationDirectory.add(path2, null,
                                   CreateMode.PERSISTENT_SEQUENTIAL);
        portLocationDirectory.delete(fullpath2);
        assertEquals(1, ovsdb.deletedPorts.size());

        // Port goes down.  Verify tunnel rm'd.
        log.info("Deleting path {}", path3);
        portLocationDirectory.delete(path3);
        assertEquals(2, ovsdb.deletedPorts.size());
        assertEquals("tnff0011ac", ovsdb.deletedPorts.get(1));
    }

    @Test
    public void testLLDPFlowMatch() {
        LLDP packet = new LLDP();
        LLDPTLV chassis = new LLDPTLV();
        chassis.setType((byte)0xca);
        chassis.setLength((short)7);
        chassis.setValue("chassis".getBytes());
        LLDPTLV port = new LLDPTLV();
        port.setType((byte)0);
        port.setLength((short)4);
        port.setValue("port".getBytes());
        LLDPTLV ttl = new LLDPTLV();
        ttl.setType((byte)40);
        ttl.setLength((short)3);
        ttl.setValue("ttl".getBytes());
        packet.setChassisId(chassis);
        packet.setPortId(port);
        packet.setTtl(ttl);

        Ethernet frame = new Ethernet();
        frame.setPayload(packet);
        frame.setEtherType(LLDP.ETHERTYPE);
        MAC dstMac = MAC.fromString("00:11:22:33:44:55");
        MAC srcMac = MAC.fromString("66:55:44:33:22:11");
        frame.setDestinationMACAddress(dstMac);
        frame.setSourceMACAddress(srcMac);
        byte[] pktData = frame.serialize();

        assertEquals(0, controllerStub.addedFlows.size());
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        controller.onPacketIn(-1, pktData.length, (short)1, pktData);
        MidoMatch expectMatch = new MidoMatch();
        expectMatch.setDataLayerType(LLDP.ETHERTYPE);
        expectMatch.setDataLayerSource(srcMac);
        expectMatch.setDataLayerDestination(dstMac);
        expectMatch.setInputPort((short)1);
        assertEquals(1, controllerStub.addedFlows.size());
        assertEquals(expectMatch, controllerStub.addedFlows.get(0).match);
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(1, controllerStub.droppedPktBufIds.size());
        assertEquals(-1, controllerStub.droppedPktBufIds.get(0).intValue());
    }

    @Test
    public void testIGMPFlowMatch() {
        // Real IGMP packet sniffed off the net.
        String frameHexDump = "01005e0000fb001b21722f2b080046c0002000004000" +
                              "01027233708c205de00000fb9404000016000904e000" +
                              "00fb0000000000000000000000000000";
        MAC srcMac = MAC.fromString("00:1b:21:72:2f:2b");
        MAC dstMac = MAC.fromString("01:00:5e:00:00:fb");
        int srcIP = Net.convertStringAddressToInt("112.140.32.93");
        int dstIP = Net.convertStringAddressToInt("224.0.0.251");

        assertEquals(60*2, frameHexDump.length());
        byte[] pktData = new byte[60];
        for (int i = 0; i < 60; i++) {
            pktData[i] = (byte)(
                (Character.digit(frameHexDump.charAt(2*i), 16) << 4) |
                (Character.digit(frameHexDump.charAt(2*i+1), 16)));
        }

        assertEquals(0, controllerStub.addedFlows.size());
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        controller.onPacketIn(76, pktData.length, (short)-1, pktData);
        MidoMatch expectMatch = new MidoMatch();
        expectMatch.setDataLayerType(IPv4.ETHERTYPE);
        expectMatch.setDataLayerSource(srcMac);
        expectMatch.setDataLayerDestination(dstMac);
        // expectMatch.setNetworkTypeOfService(diffServ);
        expectMatch.setNetworkProtocol((byte)2 /* IGMP */);
        expectMatch.setNetworkSource(srcIP);
        expectMatch.setNetworkDestination(dstIP);

        assertEquals(1, controllerStub.addedFlows.size());
        assertEquals(expectMatch, controllerStub.addedFlows.get(0).match);
        assertArrayEquals(new OFAction[]{},
                          controllerStub.addedFlows.get(0).actions.toArray());
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(0, controllerStub.droppedPktBufIds.size());

        OFAction[] flowActions = { new OFActionOutput((short)1, (short)2) };
        controller.flowActions = flowActions;
        controller.onPacketIn(-1, pktData.length, (short)-1, pktData);
        assertEquals(2, controllerStub.addedFlows.size());
        assertEquals(expectMatch, controllerStub.addedFlows.get(1).match);
        assertArrayEquals(flowActions,
                          controllerStub.addedFlows.get(1).actions.toArray());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        assertEquals(1, controllerStub.sentPackets.size());
        assertArrayEquals(pktData, controllerStub.sentPackets.get(0).data);
    }

    @Test
    public void testTCPDropAndNotDrop() {
        //TCP xport = new TCP();
        // Can't construct a TCP packet with the packets package because
        // TCP.serialize() is unimplemented.
        MAC srcMac = MAC.fromString("00:18:e7:dd:1c:b4");
        MAC dstMac = MAC.fromString("10:9a:dd:4c:6f:49");
        int srcIP = Net.convertStringAddressToInt("204.152.18.196");
        int dstIP = Net.convertStringAddressToInt("192.168.1.143");
        short srcPort = 443;
        short dstPort = (short)36911;

        /* Real Ethernet [IP/TCP] frame sniffed off the net. */
        String frameHexDump =
                "109add4c6f490018e7dd1cb408004500015f2f8d4000f106b777cc9812" +
                "c4c0a8018f01bb902f5385670e2c5417cb50189ffeb183000017030101" +
                "320c06e1c9e3d422b90c91caf2a4f773c1a8996b1f435586f21c8b03f3" +
                "24ba5c94335d89849c9552180f94826c82860cbcd7cc42990e5c9442b7" +
                "f815f46d17512e47125d295a7cc62106e058a41d944f5f43a10ef37f02" +
                "9ba63fdeffe60b63c02ec129509d37852a6f378fb9ec3d64cd186c458b" +
                "d5e9a32778c879bc1595c604b252b00f02f8baf82c17664e91ee65e093" +
                "04c5f603ac41953234e71a304790f597261f3bd593a9b1bddf085b642d" +
                "f7ffe43a554fcd09f1deff0f7ffe519a1959e695909d4db805a0b1aed5" +
                "1a06bef1b6f98106985e227f2a9ca3469553aa99a53e46a64517eb219f" +
                "c68454ae123e2868a19e209d429e60cc72e5815df1526c3e0de0e036ce" +
                "d7996e5a3d7137618ef311fcb0f8d73f6437695fee3b7f720a3db4e31b" +
                "927d5a0ff58ad57a319d2e6fae4545d3a9";
        assertEquals(365*2, frameHexDump.length());
        byte[] pktData = new byte[365];
        for (int i = 0; i < 365; i++) {
            pktData[i] = (byte)(
                (Character.digit(frameHexDump.charAt(2*i), 16) << 4) |
                (Character.digit(frameHexDump.charAt(2*i+1), 16)));
        }

        assertEquals(0, controllerStub.addedFlows.size());
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        controller.onPacketIn(76, pktData.length, (short)-1, pktData);
        MidoMatch expectMatch = new MidoMatch();
        expectMatch.setDataLayerType(IPv4.ETHERTYPE);
        expectMatch.setDataLayerSource(srcMac);
        expectMatch.setDataLayerDestination(dstMac);
        // expectMatch.setNetworkTypeOfService((byte)0);
        expectMatch.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        expectMatch.setNetworkSource(srcIP);
        expectMatch.setNetworkDestination(dstIP);
        expectMatch.setTransportSource(srcPort);
        expectMatch.setTransportDestination(dstPort);

        assertEquals(1, controllerStub.addedFlows.size());
        assertEquals(expectMatch, controllerStub.addedFlows.get(0).match);
        assertArrayEquals(new OFAction[]{},
                          controllerStub.addedFlows.get(0).actions.toArray());
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        // TODO: Is it possible to "drop" a packet with a bufferID != -1 ?
        //assertEquals(76, controllerStub.droppedPktBufIds.get(0).intValue());

        OFAction[] flowActions = { new OFActionOutput((short)1, (short)2) };
        controller.flowActions = flowActions;
        controller.onPacketIn(-1, pktData.length, (short)-1, pktData);
        assertEquals(2, controllerStub.addedFlows.size());
        assertEquals(expectMatch, controllerStub.addedFlows.get(1).match);
        assertArrayEquals(flowActions,
                          controllerStub.addedFlows.get(1).actions.toArray());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        assertEquals(1, controllerStub.sentPackets.size());
        assertArrayEquals(pktData, controllerStub.sentPackets.get(0).data);
    }

    @Test
    public void testUDPNoDrop() {
        UDP xport = new UDP();
        xport.setSourcePort((short)17234);
        xport.setDestinationPort((short)52956);
        IPv4 packet = new IPv4();
        packet.setPayload(xport);
        packet.setProtocol(UDP.PROTOCOL_NUMBER);
        packet.setDiffServ((byte)0x14);
        Ethernet frame = new Ethernet();
        frame.setPayload(packet);
        frame.setEtherType(IPv4.ETHERTYPE);
        MAC dstMac = MAC.fromString("00:11:22:33:44:55");
        MAC srcMac = MAC.fromString("66:55:44:33:22:11");
        frame.setDestinationMACAddress(dstMac);
        frame.setSourceMACAddress(srcMac);
        byte[] pktData = frame.serialize();

        OFAction[] flowActions = { new OFActionOutput((short)1, (short)2) };
        controller.flowActions = flowActions;
        assertEquals(0, controllerStub.addedFlows.size());
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        controller.onPacketIn(-1, pktData.length, (short)-1, pktData);
        MidoMatch expectMatch = new MidoMatch();
        expectMatch.setDataLayerType(IPv4.ETHERTYPE);
        expectMatch.setDataLayerSource(srcMac);
        expectMatch.setDataLayerDestination(dstMac);
        // expectMatch.setNetworkTypeOfService((byte)0x14);
        expectMatch.setNetworkProtocol(UDP.PROTOCOL_NUMBER);
        expectMatch.setNetworkSource(0);
        expectMatch.setNetworkDestination(0);
        expectMatch.setTransportSource((short)17234);
        expectMatch.setTransportDestination((short)52956);
        assertEquals(1, controllerStub.addedFlows.size());
        assertEquals(expectMatch, controllerStub.addedFlows.get(0).match);
        assertArrayEquals(flowActions,
                          controllerStub.addedFlows.get(0).actions.toArray());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        assertEquals(1, controllerStub.sentPackets.size());
        assertArrayEquals(pktData, controllerStub.sentPackets.get(0).data);
    }
}
