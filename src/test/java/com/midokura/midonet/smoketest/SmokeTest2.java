/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.PeerRouterLink;
import com.midokura.midonet.smoketest.topology.InternalPort;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.TapPort;
import com.midokura.midonet.smoketest.topology.Tenant;

public class SmokeTest2 {

    static Tenant tenant1;
    static Tenant tenant2;
    static PeerRouterLink rtrLink;
    static TapPort tapPort;
    static InternalPort internalPort;
    static OpenvSwitchDatabaseConnection ovsdb;
    static PacketHelper helper;

    @BeforeClass
    public static void setUp() {
        ovsdb =  new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        MockMidolmanMgmt mgmt = new MockMidolmanMgmt(true);
        tenant1 = new Tenant.Builder(mgmt).setName("tenant1").build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();
        tapPort = router1.addPort(ovsdb).setDestination("192.168.100.2")
                .buildTap();
        helper = new PacketHelper(tapPort.getInnerMAC(), "192.168.100.2");

        tenant2 = new Tenant.Builder(mgmt).setName("tenant2").build();
        Router router2 = tenant2.addRouter().setName("rtr1").build();
        internalPort = router2.addPort(ovsdb).setDestination("192.168.101.2")
                .buildInternal();

        rtrLink = router1.addRouterLink().setPeer(router2).
                setLocalPrefix("192.168.100.0").setPeerPrefix("192.168.100.0").
                build();
    }

    @AfterClass
    public static void tearDown() {
        rtrLink.delete();
        tenant1.delete();
        tenant2.delete();
        ovsdb.delBridge("smoke-br");
    }

    @Test
    public void testDhcpClient() {
        // Send the DHCP Discover
        byte[] request = helper.makeDhcpDiscover();
        assertTrue(tapPort.send(request));
        byte[] reply = tapPort.recv();
        helper.checkDhcpOffer(request, reply, "192.168.100.2");

        // Send the DHCP Request
        request = helper.makeDhcpRequest(reply);
        assertTrue(tapPort.send(request));
        helper.checkDhcpAck(request, tapPort.recv());
    }

    @Test
    public void testPingTapToInternal() {
        /* Ping my router's port, then ping the peer port. */

        // Note: the router port's own MAC is the tap's hwAddr.
        MAC rtrMac = tapPort.getOuterMAC();
        IntIPv4 rtrIp = IntIPv4.fromString("192.168.100.1");

        // First arp for router's mac.
        byte[] request = helper.makeArpRequest(rtrIp);
        assertTrue(tapPort.send(request));
        byte[] reply = tapPort.recv();
        helper.checkArpReply(reply, rtrMac, rtrIp);

        // Ping router's port.
        request = helper.makeIcmpEchoRequest(rtrMac, rtrIp);
        assertTrue(tapPort.send(request));
        // Note: Midolman's virtual router currently does not ARP before
        // responding to ICMP echo requests addressed to its own port.
        helper.checkIcmpEchoReply(request, tapPort.recv());

        // Ping peer port.
        IntIPv4 peerIp = IntIPv4.fromString("192.168.101.2");
        request = helper.makeIcmpEchoRequest(rtrMac, peerIp);
        assertTrue(tapPort.send(request));
        // Note: the virtual router ARPs before delivering the packet.
        byte[] arp = tapPort.recv();
        helper.checkArpRequest(arp, rtrMac, rtrIp);
        assertTrue(tapPort.send(helper.makeArpReply(rtrMac, rtrIp)));
        // Finally, the icmp echo reply from the peer.
        helper.checkIcmpEchoReply(request, tapPort.recv());

        // No other packets arrive at the port.
        assertNull(tapPort.recv());
    }
}
