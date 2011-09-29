/* Copyright 2011 Midokura Inc. */

package com.midokura.midolman;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.openflow.protocol.action.OFAction;
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
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.util.Net;


class AbstractControllerTester extends AbstractController {
    public List<OFPhysicalPort> portsAdded;
    public List<OFPhysicalPort> portsRemoved;
    public int numClearCalls;
    short flowExpireSeconds;
    short idleFlowExpireSeconds;
 
    public AbstractControllerTester(
            int datapathId,
            UUID switchUuid,
            int greKey,
            OpenvSwitchDatabaseConnection ovsdb,
            PortToIntNwAddrMap dict,
            short flowExpireSeconds,
            long idleFlowExpireMillis,
            InetAddress internalIp) {
        super(datapathId, switchUuid, greKey, ovsdb, dict, internalIp, 
              "midonet");
        portsAdded = new ArrayList<OFPhysicalPort>();
        portsRemoved = new ArrayList<OFPhysicalPort>();
        numClearCalls = 0;
        this.flowExpireSeconds = flowExpireSeconds;
        this.idleFlowExpireSeconds = (short)(idleFlowExpireMillis/1000);
    }

    @Override 
    public void onPacketIn(int bufferId, int totalLen, short inPort,
                           byte[] data) { 
        Ethernet frame = new Ethernet();
        frame.deserialize(data, 0, data.length);
        OFMatch match = createMatchFromPacket(frame, inPort);
        addFlowAndPacketOut(match, 1040, idleFlowExpireSeconds,
                            flowExpireSeconds, (short)1000, bufferId, true, 
                            false, false, new OFAction[]{}, inPort, data);
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie,
            short priority, OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) { }

    public void clear() {
        portsAdded = new ArrayList<OFPhysicalPort>();
        portsRemoved = new ArrayList<OFPhysicalPort>();
        numClearCalls++;
    }

    @Override
    protected void addPort(OFPhysicalPort portDesc, short portNum) { 
        assertEquals(portDesc.getPortNumber(), portNum);
        portsAdded.add(portDesc);
    }

    @Override
    protected void deletePort(OFPhysicalPort portDesc) { 
        portsRemoved.add(portDesc);
    }

    @Override 
    protected void portMoved(UUID portUuid, Integer oldAddr, Integer newAddr) {
        // Do nothing.
    }

    public void setFeatures(OFFeaturesReply features) {
        ((MockControllerStub) controllerStub).setFeatures(features);
    }

    @Override
    public String makeGREPortName(int a) {
        return super.makeGREPortName(a);
    }

    @Override
    public Integer peerIpOfGrePortName(String s) {
        return super.peerIpOfGrePortName(s);
    }
}


public class TestAbstractController {

    private AbstractControllerTester controller;

    private OFPhysicalPort port1;
    private OFPhysicalPort port2;
    private UUID port1uuid;
    private UUID port2uuid;
    private int dp_id;
    private MockOpenvSwitchDatabaseConnection ovsdb;
    private PortToIntNwAddrMap portLocMap;
    private MockDirectory mockDir;
    private MockControllerStub controllerStub = new MockControllerStub();

    public Logger log = LoggerFactory.getLogger(TestAbstractController.class);

    @Before
    public void setUp() {
        dp_id = 43;
        ovsdb = new MockOpenvSwitchDatabaseConnection();

        mockDir = new MockDirectory();
        portLocMap = new PortToIntNwAddrMap(mockDir);

        controller = new AbstractControllerTester(
                             dp_id /* datapathId */,
                             UUID.randomUUID() /* switchUuid */,
                             0xe1234 /* greKey */,
                             ovsdb /* ovsdb */,
                             portLocMap /* portLocationMap */,
                             (short)300 /* flowExpireSeconds */,
                             60 * 1000 /* idleFlowExpireMillis */,
                             null /* internalIp */);
        controller.setControllerStub(controllerStub);

        port1 = new OFPhysicalPort();
        port1.setPortNumber((short) 37);
        port1.setHardwareAddress(new byte[] { 10, 12, 13, 14, 15, 37 });
        port1uuid = UUID.randomUUID();
        ovsdb.setPortExternalId(dp_id, 37, "midonet", port1uuid.toString());

        port2 = new OFPhysicalPort();
        port2.setPortNumber((short) 47);
        port2.setHardwareAddress(new byte[] { 10, 12, 13, 14, 15, 47 });
        port2.setName("tne12340a001122");
        port2uuid = UUID.randomUUID();
        ovsdb.setPortExternalId(dp_id, 47, "midonet", port2uuid.toString());

        controller.onPortStatus(port1, OFPortReason.OFPPR_ADD);
        controller.onPortStatus(port2, OFPortReason.OFPPR_ADD);
    }

    @Test
    public void testPortMap() throws UnknownHostException {
        assertEquals(37, controller.portUuidToNumber(port1uuid));
        assertEquals(47, controller.portUuidToNumber(port2uuid));
        assertFalse(controller.isTunnelPortNum(37));
        assertTrue(controller.isTunnelPortNum(47));
        assertEquals(null, controller.peerOfTunnelPortNum(37));
        assertEquals(0x0a001122, controller.peerOfTunnelPortNum(47).intValue());
    }

    @Test
    public void testConnectionMade() {
        OFFeaturesReply features = new OFFeaturesReply();
        ArrayList<OFPhysicalPort> portList = new ArrayList<OFPhysicalPort>();
        portList.add(port1);    // Regular port, gets recorded.
        portList.add(port2);    // Tunnel port, gets deleted.
        features.setPorts(portList);
        controller.setFeatures(features);
        controller.onConnectionLost();
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.portsAdded.toArray());
        assertArrayEquals(new String[] { },
                          ovsdb.deletedPorts.toArray());
        MockControllerStub stub = 
                (MockControllerStub) controller.controllerStub;
        assertEquals(0, stub.deletedFlows.size());
        controller.onConnectionMade();
        assertArrayEquals(new OFPhysicalPort[] { port1 },
                          controller.portsAdded.toArray());
        assertArrayEquals(new String[] { port2.getName() },
                          ovsdb.deletedPorts.toArray());
        assertEquals(1, stub.deletedFlows.size());
        assertEquals(OFMatch.OFPFW_ALL, 
                     stub.deletedFlows.get(0).match.getWildcards());
    }

    @Test
    public void testClearAdd() {
        assertArrayEquals(new OFPhysicalPort[] { port1, port2 },
                          controller.portsAdded.toArray());
        assertEquals(0, controller.numClearCalls);
        controller.onConnectionLost();
        assertEquals(1, controller.numClearCalls);
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.portsAdded.toArray());
        controller.onPortStatus(port1, OFPortReason.OFPPR_ADD);
        controller.onPortStatus(port2, OFPortReason.OFPPR_ADD);
        assertArrayEquals(new OFPhysicalPort[] { port1, port2 },
                          controller.portsAdded.toArray());
        assertEquals(port1uuid, controller.portNumToUuid.get(37));
        assertEquals(port2uuid, controller.portNumToUuid.get(47));
        assertNull(controller.peerOfTunnelPortNum(37));
        assertEquals(Net.convertStringAddressToInt("10.0.17.34"),
                     controller.peerOfTunnelPortNum(47).intValue());
    }

    @Test
    public void testModifyPort() {
        port2.setName("tne12340a001123");
        UUID port2newUuid = UUID.randomUUID();
        ovsdb.setPortExternalId(dp_id, 47, "midonet", port2newUuid.toString());
        controller.onPortStatus(port2, OFPortReason.OFPPR_MODIFY);
        assertEquals(port2newUuid, controller.portNumToUuid.get(47));
        assertEquals(Net.convertStringAddressToInt("10.0.17.35"),
                     controller.peerOfTunnelPortNum(47).intValue());
    }

    @Test
    public void testDeletePort() {
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.portsRemoved.toArray());
        assertTrue(controller.portNumToUuid.containsKey(37));
        assertNull(controller.peerOfTunnelPortNum(37));
        controller.onPortStatus(port1, OFPortReason.OFPPR_DELETE);
        assertArrayEquals(new OFPhysicalPort[] { port1 },
                          controller.portsRemoved.toArray());
        assertFalse(controller.portNumToUuid.containsKey(37));
        assertTrue(controller.portNumToUuid.containsKey(47));
        assertNotNull(controller.peerOfTunnelPortNum(47));
        controller.onPortStatus(port2, OFPortReason.OFPPR_DELETE);
        assertArrayEquals(new OFPhysicalPort[] { port1, port2 },
                          controller.portsRemoved.toArray());
        assertFalse(controller.portNumToUuid.containsKey(47));
        assertNull(controller.peerOfTunnelPortNum(47));
    }

    @Test
    public void testMakeGREPortName() {
        assertEquals("tne1234ff0011aa", controller.makeGREPortName(0xff0011aa));
    }

    @Test
    public void testPeerIpOfGrePortName() {
        assertEquals(0xff0011aa,
                controller.peerIpOfGrePortName("tne1234ff0011aa").intValue());
    }

    @Test
    public void testPeerIpToTunnelPortNum() {
        int peerIpInt = Net.convertStringAddressToInt("192.168.1.53");
        String grePortName = controller.makeGREPortName(peerIpInt);
        Integer peerIp = controller.peerIpOfGrePortName(grePortName);
        assertEquals(new Integer(peerIpInt), peerIp);

        OFPhysicalPort port = new OFPhysicalPort();
        port.setPortNumber((short) 54);
        port.setHardwareAddress(new byte[] { 10, 12, 13, 14, 15, 54 });
        port.setName(grePortName);
        ovsdb.setPortExternalId(dp_id, 54, "midonet", 
                                UUID.randomUUID().toString());
        controller.onPortStatus(port, OFPortReason.OFPPR_ADD);
        log.debug("peerIpInt: {}", peerIpInt);
        assertEquals(new Integer(54), 
                     controller.tunnelPortNumOfPeer(peerIpInt));
    }

    @Test
    public void testPortLocMapListener() throws KeeperException {
        UUID portUuid = UUID.randomUUID();
        String path1 = "/"+portUuid.toString()+",255.0.17.170,";
        String path2 = "/"+portUuid.toString()+",255.0.17.172,";

        // Port comes up.  Verify tunnel made.
        String fullpath1 = mockDir.add(path1, null,
                                       CreateMode.PERSISTENT_SEQUENTIAL);
        portLocMap.start();
        assertEquals(1, ovsdb.addedGrePorts.size());
        assertTrue((new MockOpenvSwitchDatabaseConnection.GrePort(
                            "43", "tne1234ff0011aa", "255.0.17.170")).equals(
                   ovsdb.addedGrePorts.get(0)));
        assertEquals(0xff0011aa, portLocMap.get(portUuid).intValue());
        assertEquals(0, ovsdb.deletedPorts.size());

        // Port moves.  Verify old tunnel rm'd, new tunnel made.
        String fullpath2 = mockDir.add(path2, null,
                                       CreateMode.PERSISTENT_SEQUENTIAL);
        mockDir.delete(fullpath1);
        assertEquals(2, ovsdb.addedGrePorts.size());
        assertTrue((new MockOpenvSwitchDatabaseConnection.GrePort(
                            "43", "tne1234ff0011ac", "255.0.17.172")).equals(
                   ovsdb.addedGrePorts.get(1)));
        assertEquals(0xff0011ac, portLocMap.get(portUuid).intValue());
        assertEquals(1, ovsdb.deletedPorts.size());
        assertEquals("tne1234ff0011aa", ovsdb.deletedPorts.get(0));

        // Port doesn't move.  Verify tunnel not rm'd.
        String path3 = mockDir.add(path2, null,
                                   CreateMode.PERSISTENT_SEQUENTIAL);
        mockDir.delete(fullpath2);
        assertEquals(1, ovsdb.deletedPorts.size());

        // Port goes down.  Verify tunnel rm'd.
        log.info("Deleting path {}", path3);
        mockDir.delete(path3);
        assertEquals(2, ovsdb.deletedPorts.size());
        assertEquals("tne1234ff0011ac", ovsdb.deletedPorts.get(1));
    }

    @Test
    public void testGetGreKey() {
        assertEquals(0xe1234, controller.getGreKey());
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

    // FIXME: Test UDP without drop, TCP with drop & bufferId != -1
}
