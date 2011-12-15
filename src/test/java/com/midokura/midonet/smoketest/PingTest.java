/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import com.midokura.midonet.smoketest.topology.InternalPort;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.TapPort;
import com.midokura.midonet.smoketest.topology.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public class PingTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory.getLogger(PingTest.class);

    static Tenant tenant1;
    static TapPort tapPort;
    static InternalPort internalPort;
    static IntIPv4 rtrIp;
    static IntIPv4 peerIp;
    static OpenvSwitchDatabaseConnection ovsdb;
    static PacketHelper helper;
    static MidolmanMgmt mgmt;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        // First clean up left-overs from previous incomplete tests.
        // Process p = Runtime.getRuntime().exec(
        // "sudo -n ip tuntap del dev tapPort1 mode tap");
        // p.waitFor();
        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        Random rand = new Random(System.currentTimeMillis());
        String tenantName = "tenant" + rand.nextInt();
        tenant1 = new Tenant.Builder(mgmt).setName(tenantName).build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();

        IntIPv4 tapIp = IntIPv4.fromString("192.168.231.2");
        rtrIp = IntIPv4.fromString("192.168.231.1");
        tapPort = router1.addPort(ovsdb).setDestination(tapIp.toString())
                .buildTap();
        helper = new PacketHelper(tapPort.getInnerMAC(), tapIp,
                tapPort.getOuterMAC(), rtrIp);

        peerIp = IntIPv4.fromString("192.168.231.3");
        internalPort =
            router1
                .addPort(ovsdb)
                .setDestination(peerIp.toString())
                .buildInternal();

        Thread.sleep(10*1000);
    }

    @AfterClass
    public static void tearDown() {
        ovsdb.delBridge("smoke-br");

        removePort(internalPort);
        removePort(tapPort);
        removeTenant(tenant1);

        mgmt.stop();

        resetZooKeeperState(log);
    }

    @Test
    public void test() {
        byte[] request;

        // First arp for router's mac.
        assertTrue(tapPort.send(helper.makeArpRequest()));
        helper.checkArpReply(tapPort.recv());

        // Ping router's port.
        request = helper.makeIcmpEchoRequest(rtrIp);
        assertThat("The tap should have sent the packet", tapPort.send(request));
        // Note: Midolman's virtual router currently does not ARP before
        // responding to ICMP echo requests addressed to its own port.
        helper.checkIcmpEchoReply(request, tapPort.recv());

        // Ping peer port.
        request = helper.makeIcmpEchoRequest(peerIp);
        assertThat("The tap should have sent the packet again", tapPort.send(request));
        // Note: the virtual router ARPs before delivering the reply packet.
        helper.checkArpRequest(tapPort.recv());
        assertThat("The tap should have sent the packet again", tapPort.send(helper.makeArpReply()));
        // Finally, the icmp echo reply from the peer.
        helper.checkIcmpEchoReply(request, tapPort.recv());

        // No other packets arrive at the port.
        assertNull(tapPort.recv());
    }
}
