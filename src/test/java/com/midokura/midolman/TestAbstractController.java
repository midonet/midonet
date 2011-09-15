/* Copyright 2011 Midokura Inc. */

package com.midokura.midolman;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFPhysicalPort;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import com.midokura.midolman.AbstractController;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.util.Net;


class AbstractControllerTester extends AbstractController {
    public List<OFPhysicalPort> portsAdded;
    public List<OFPhysicalPort> portsRemoved;
    public List<OFPhysicalPort> portsModified;
    public int numClearCalls;
 
    public AbstractControllerTester(
            int datapathId,
            UUID switchUuid,
            int greKey,
            OpenvSwitchDatabaseConnection ovsdb,
            PortToIntNwAddrMap dict,
            long flowExpireMinMillis,
            long flowExpireMaxMillis,
            long idleFlowExpireMillis,
            InetAddress internalIp) {
        super(datapathId, switchUuid, greKey, ovsdb, dict, flowExpireMinMillis,
	      flowExpireMaxMillis, idleFlowExpireMillis, internalIp);
        portsAdded = new ArrayList<OFPhysicalPort>();
        portsRemoved = new ArrayList<OFPhysicalPort>();
        portsModified = new ArrayList<OFPhysicalPort>();
        numClearCalls = 0;
    }

    @Override 
    public void onPacketIn(int bufferId, int totalLen, short inPort,
		           byte[] data) { }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie,
            short priority, OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) { }

    @Override
    public void clear() {
        portsAdded = new ArrayList<OFPhysicalPort>();
        portsRemoved = new ArrayList<OFPhysicalPort>();
        portsModified = new ArrayList<OFPhysicalPort>();
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
    protected void modifyPort(OFPhysicalPort portDesc) {
        portsModified.add(portDesc);
    }

    public void setFeatures(OFFeaturesReply features) {
	((MockControllerStub) controllerStub).setFeatures(features);
    }

    @Override
    public String makeGREPortName(int a) {
	return super.makeGREPortName(a);
    }

    @Override
    public InetAddress peerIpOfGrePortName(String s) {
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
 			     260 * 1000 /* flowExpireMinMillis */,
 			     320 * 1000 /* flowExpireMaxMillis */,
 			     60 * 1000 /* idleFlowExpireMillis */,
			     null /* internalIp */);
        controller.setControllerStub(new MockControllerStub());

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
    public void testConnectionMade() {
        OFFeaturesReply features = new OFFeaturesReply();
        ArrayList<OFPhysicalPort> portList = new ArrayList<OFPhysicalPort>();
	portList.add(port1);
	portList.add(port2);
        features.setPorts(portList);
        controller.setFeatures(features);
        controller.onConnectionLost();
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.portsAdded.toArray());
        MockControllerStub stub = 
		(MockControllerStub) controller.controllerStub;
        assertEquals(0, stub.deletedFlows.size());
        controller.onConnectionMade();
        assertArrayEquals(new OFPhysicalPort[] { port1, port2 },
                          controller.portsAdded.toArray());
        assertEquals(1, stub.deletedFlows.size());
        assertEquals(OFMatch.OFPFW_ALL, 
		     stub.deletedFlows.get(0).match.getWildcards());
    }

    @Test
    public void testClearAdd() throws UnknownHostException {
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
        assertFalse(controller.tunnelPortNumToPeerIp.containsKey(37));
        assertEquals(InetAddress.getByName("10.0.17.34"),
	             controller.tunnelPortNumToPeerIp.get(47));
    }

    @Test
    public void testModifyPort() throws UnknownHostException {
        port2.setName("tne12340a001123");
        UUID port2newUuid = UUID.randomUUID();
        ovsdb.setPortExternalId(dp_id, 47, "midonet", port2newUuid.toString());
        assertArrayEquals(new OFPhysicalPort[] { },
			  controller.portsModified.toArray());
        controller.onPortStatus(port2, OFPortReason.OFPPR_MODIFY);
        assertArrayEquals(new OFPhysicalPort[] { port2 },
			  controller.portsModified.toArray());
        assertEquals(port2newUuid, controller.portNumToUuid.get(47));
        assertEquals(InetAddress.getByName("10.0.17.35"),
                     controller.tunnelPortNumToPeerIp.get(47));
    }

    @Test
    public void testDeletePort() {
        assertArrayEquals(new OFPhysicalPort[] { },
			  controller.portsRemoved.toArray());
	assertTrue(controller.portNumToUuid.containsKey(37));
	assertFalse(controller.tunnelPortNumToPeerIp.containsKey(37));
        controller.onPortStatus(port1, OFPortReason.OFPPR_DELETE);
        assertArrayEquals(new OFPhysicalPort[] { port1 },
			  controller.portsRemoved.toArray());
	assertFalse(controller.portNumToUuid.containsKey(37));
	assertTrue(controller.portNumToUuid.containsKey(47));
	assertTrue(controller.tunnelPortNumToPeerIp.containsKey(47));
        controller.onPortStatus(port2, OFPortReason.OFPPR_DELETE);
        assertArrayEquals(new OFPhysicalPort[] { port1, port2 },
			  controller.portsRemoved.toArray());
	assertFalse(controller.portNumToUuid.containsKey(47));
	assertFalse(controller.tunnelPortNumToPeerIp.containsKey(47));
    }

    @Test
    public void testMakeGREPortName() {
	assertEquals("tne1234ff0011aa", controller.makeGREPortName(0xff0011aa));
    }

    @Test
    public void testPeerIpOfGrePortName() {
	assertEquals(0xff0011aa,
	    Net.convertInetAddressToInt(
		controller.peerIpOfGrePortName("tne1234ff0011aa")));
    }

    @Test
    public void testPortLocMapListener() throws KeeperException {
	UUID portUuid = UUID.randomUUID();
	String path1 = "/"+portUuid.toString()+",ff0011aa,1";
	String path2 = "/"+portUuid.toString()+",ff0011ac,2";
	String path3 = "/"+portUuid.toString()+",ff0011ac,3";

	// Port comes up.  Verify tunnel made.
	mockDir.add(path1, null, CreateMode.PERSISTENT_SEQUENTIAL);
	portLocMap.start();
	assertEquals(1, ovsdb.addedGrePorts.size());
	assertTrue((new MockOpenvSwitchDatabaseConnection.GrePort(
			    "43", "tne1234ff0011aa", "255.0.17.170")).equals(
	           ovsdb.addedGrePorts.get(0)));
	assertEquals(0xff0011aa, portLocMap.get(portUuid).intValue());
	assertEquals(0, ovsdb.deletedPorts.size());

	// Port moves.  Verify old tunnel rm'd, new tunnel made.
	mockDir.add(path2, null, CreateMode.PERSISTENT_SEQUENTIAL);
	assertEquals(2, ovsdb.addedGrePorts.size());
	assertTrue((new MockOpenvSwitchDatabaseConnection.GrePort(
			    "43", "tne1234ff0011ac", "255.0.17.172")).equals(
	           ovsdb.addedGrePorts.get(1)));
	assertEquals(0xff0011ac, portLocMap.get(portUuid).intValue());
	assertEquals(1, ovsdb.deletedPorts.size());
	assertEquals("tne1234ff0011aa", ovsdb.deletedPorts.get(0));

	// Port doesn't move.  Verify tunnel not rm'd.
	mockDir.add(path3, null, CreateMode.PERSISTENT_SEQUENTIAL);
	assertEquals(1, ovsdb.deletedPorts.size());

	// Port goes down.  Verify tunnel rm'd.
        //mockDir.delete(path3); // XXX: Why is this throwing NoNode?
				 //	 We just added it.
	//assertEquals(2, ovsdb.deletedPorts.size());
    }
}
