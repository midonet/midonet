/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.InternalPort;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.TapPort;
import com.midokura.midonet.smoketest.topology.Tenant;

import java.util.Random;

public class StatsTest {
    static Tenant tenant1;
    static TapPort tapPort1;
    static TapPort tapPort2;
    static IntIPv4 tapIp1;
    static IntIPv4 tapIp2;
    static IntIPv4 rtrIp;
    static IntIPv4 intPortIp;
    static PacketHelper helper1;
    static PacketHelper helper2;
    static OpenvSwitchDatabaseConnection ovsdb;
    static MidolmanMgmt mgmt;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        // First clean up left-overs from previous incomplete tests.
        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        Random rand = new Random(System.currentTimeMillis());
        String tenantName = "tenant" + rand.nextInt();
        tenant1 = new Tenant.Builder(mgmt).setName(tenantName).build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();

        rtrIp = IntIPv4.fromString("192.168.100.1");
        tapIp1 = IntIPv4.fromString("192.168.100.11");
        tapIp2 = IntIPv4.fromString("192.168.100.12");
        intPortIp = IntIPv4.fromString("192.168.100.13");
        tapPort1 = router1.addPort(ovsdb).setDestination(tapIp1.toString())
                .buildTap();
        helper1 = new PacketHelper(tapPort1.getInnerMAC(), tapIp1,
                tapPort1.getOuterMAC(), rtrIp);

        tapPort2 = router1.addPort(ovsdb).setDestination(tapIp2.toString())
                .buildTap();
        helper2 = new PacketHelper(tapPort2.getInnerMAC(), tapIp2,
                tapPort2.getOuterMAC(), rtrIp);

        InternalPort internalPort = router1.addPort(ovsdb)
                .setDestination(intPortIp.toString()).buildInternal();

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

        // First arp for router's mac.
        assertTrue(tapPort1.send(helper1.makeArpRequest()));
        helper1.checkArpReply(tapPort1.recv());

        // Ping internal port from tap1.
        for (int i = 0; i < 5; i++) {
            request = helper1.makeIcmpEchoRequest(intPortIp);
            assertTrue(tapPort1.send(request));
            if (0 == i) {
                // The router ARPs before delivering the reply packet.
                helper1.checkArpRequest(tapPort1.recv());
                assertTrue(tapPort1.send(helper1.makeArpReply()));
            }
            helper1.checkIcmpEchoReply(request, tapPort1.recv());
        }

        // Ping internal port from tap1.
        for (int i = 0; i < 5; i++) {
            request = helper2.makeIcmpEchoRequest(intPortIp);
            assertTrue(tapPort2.send(request));
            if (0 == i) {
                // The router ARPs before delivering the reply packet.
                helper2.checkArpRequest(tapPort2.recv());
                assertTrue(tapPort2.send(helper2.makeArpReply()));
            }
            helper2.checkIcmpEchoReply(request, tapPort2.recv());
        }
    }
}
