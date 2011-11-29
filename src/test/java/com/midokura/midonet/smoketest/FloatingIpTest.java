/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import static com.midokura.tools.process.ProcessHelper.newProcess;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.InternalPort;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.TapPort;
import com.midokura.midonet.smoketest.topology.Tenant;

import java.util.Random;

public class FloatingIpTest {
    private final static Logger log = LoggerFactory
            .getLogger(FloatingIpTest.class);
    static Tenant tenant1;
    static TapPort tapPort1;
    static TapPort tapPort2;
    static IntIPv4 rtrIp;
    static IntIPv4 pubAddr;
    static IntIPv4 privAddr;
    static OpenvSwitchDatabaseConnection ovsdb;
    static PacketHelper helper1;
    static PacketHelper helper2;
    static MidolmanMgmt mgmt;
    static Random rand = new Random(System.currentTimeMillis());

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant" + rand.nextInt())
                .build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();

        IntIPv4 tapAddr1 = IntIPv4.fromString("192.168.66.2");
        tapPort1 = router1.addPort(ovsdb).setDestination(tapAddr1.toString())
                .buildTap();
        rtrIp = IntIPv4.fromString("192.168.66.1");
        helper1 = new PacketHelper(tapPort1.getInnerMAC(), tapAddr1,
                tapPort1.getOuterMAC(), rtrIp);

        IntIPv4 tapAddr2 = IntIPv4.fromString("192.168.66.3");
        tapPort2 = router1.addPort(ovsdb).setDestination(tapAddr2.toString())
                .buildTap();
        helper2 = new PacketHelper(tapPort1.getInnerMAC(), tapAddr2,
                tapPort2.getOuterMAC(), rtrIp);

        // The internal port has private address 192.168.55.5; floating ip
        // 10.0.0.5 is mapped to 192.168.55.5. Treat tapPort1 as the uplink:
        // only packets that go via the uplink use the the floatingIP.
        privAddr = IntIPv4.fromString("192.168.55.5");
        pubAddr = IntIPv4.fromString("10.0.0.5");
        InternalPort internalPort = router1.addPort(ovsdb)
                .setDestination(privAddr.toString()).buildInternal();
        router1.addFloatingIp(privAddr, pubAddr, tapPort1.getId());

        // The host OS needs a route to 192.168.66.0/24 via the internal port,
        // otherwise response packets from that port will go to the OS default
        // route (and not to Midonet).
        newProcess(
                String.format("sudo -n route add -net 192.168.66.0/24 dev %s",
                        internalPort.getName())).logOutput(log,
                "add_host_route").runAndWait();

        Thread.sleep(1000);
    }

    @AfterClass
    public static void tearDown() {
        ovsdb.delBridge("smoke-br");
        tapPort1.remove();
        tapPort2.remove();
        tenant1.delete();
    }

    @Test
    public void test() {
        byte[] request;

        // ICMP echo request to the floatingIP from tapPort1.
        request = helper1.makeIcmpEchoRequest(pubAddr);
        assertTrue(tapPort1.send(request));
        // Midolman's virtual router ARPs before delivering the response.
        helper1.checkArpRequest(tapPort1.recv());
        assertTrue(tapPort1.send(helper1.makeArpReply()));
        // Now the icmp echo reply from the peer.
        helper1.checkIcmpEchoReply(request, tapPort1.recv());

        // ICMP echo request to the private IP from tapPort1.
        request = helper1.makeIcmpEchoRequest(pubAddr);
        assertTrue(tapPort1.send(request));
        // No arp request this time since our earlier reply was cached.
        // Note that the ICMP reply is from the floatingIP not privAddr.
        helper1.checkIcmpEchoReply(request, tapPort1.recv(), privAddr);

        // ICMP echo request to the private IP from tapPort2.
        request = helper2.makeIcmpEchoRequest(privAddr);
        assertTrue(tapPort2.send(request));
        // Midolman's virtual router ARPs before delivering the response.
        helper2.checkArpRequest(tapPort2.recv());
        assertTrue(tapPort2.send(helper2.makeArpReply()));
        // Now the icmp echo reply from the peer.
        helper2.checkIcmpEchoReply(request, tapPort2.recv());

        // ICMP echo request to the floating IP from tapPort2.
        request = helper2.makeIcmpEchoRequest(pubAddr);
        assertTrue(tapPort1.send(request));
        // No arp request this time since our earlier reply was cached.
        // In addition, the floatingIP is not translated for packets entering
        // ports other than tapPort1. Since there's no route to the floatingIP
        // the router should return an ICMP !N.
        helper1.checkIcmpError(tapPort1.recv(),
                ICMP.UNREACH_CODE.UNREACH_NET, rtrIp, request);

        // No other packets arrive at the tap ports.
        assertNull(tapPort1.recv());
        assertNull(tapPort2.recv());
    }
}
