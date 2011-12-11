/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midonet.smoketest.topology.*;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BridgeTest {

    static Tenant tenant1;
    static IntIPv4 ip1;
    static IntIPv4 ip2;
    static IntIPv4 ip3;
    static PacketHelper helper1_3;
    static PacketHelper helper3_1;
    static PacketHelper helper3;
    static OpenvSwitchDatabaseConnection ovsdb;
    static MidolmanMgmt mgmt;
    static BridgePort bPort1;
    static BridgePort bPort2;
    static BridgePort bPort3;
    static Bridge bridge1;
    static TapWrapper tap1;
    static TapWrapper tap2;
    static TapWrapper tap3;
    static OvsBridge ovsBridge1;
    static OvsBridge ovsBridge2;

    static Random rand = new Random(System.currentTimeMillis());

    @BeforeClass
    public static void setUp() throws InterruptedException {

        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        if (ovsdb.hasBridge("smoke-br2"))
            ovsdb.delBridge("smoke-br2");

        ovsBridge1 = new OvsBridge(ovsdb, "smoke-br");
        ovsBridge2 = new OvsBridge(ovsdb, "smoke-br2", "tcp:127.0.0.1:12334");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant" + rand.nextInt())
                .build();
        bridge1 = tenant1.addBridge().setName("br1").build();

        ip1 = IntIPv4.fromString("192.168.100.2");
        bPort1 = bridge1.addPort();
        tap1 = new TapWrapper("tap1");
        ovsBridge1.addSystemPort(bPort1.getId(), tap1.getName());


        ip2 = IntIPv4.fromString("192.168.100.3");
        bPort2 = bridge1.addPort();
        tap2 = new TapWrapper("tap2");
        ovsBridge1.addSystemPort(bPort2.getId(),tap2.getName());

        ip3 = IntIPv4.fromString("192.168.100.4");
        bPort3 = bridge1.addPort();
        tap3 = new TapWrapper("tap3");
        ovsBridge2.addSystemPort(bPort3.getId(),tap3.getName());

        helper1_3 = new PacketHelper(tap1.getHwAddr(), ip1,
                tap3.getHwAddr(), ip3);
        helper3_1 = new PacketHelper(tap3.getHwAddr(), ip3,
                tap1.getHwAddr(), ip1);

        Thread.sleep(1000);
    }

    @AfterClass
    public static void tearDown() {
        // TODO (rossella): delete
    }

    @Test
    public void testPingOverBridge() {

        byte[] sent;

        sent = helper1_3.makeIcmpEchoRequest(ip3);
        assertTrue(tap1.send(sent));
        // Note: the virtual router ARPs before delivering the IPv4 packet.
        helper3_1.checkArpRequest(tap3.recv());
        assertTrue(tap3.send(helper3_1.makeArpReply()));
        // receive the icmp
        helper3_1.checkIcmpEchoRequest(sent, tap3.recv());

        sent = helper3_1.makeIcmpEchoRequest(ip1);
        assertTrue(tap3.send(sent));
        // Note: the virtual router ARPs before delivering the IPv4 packet.
       // helper1_3.checkArpRequest(tapPort1.recv());
       // assertTrue(tap1.send(helper1_3.makeArpReply()));
        // receive the icmp
        helper1_3.checkIcmpEchoRequest(sent, tap1.recv());


        // VM moves
        sent = helper3_1.makeIcmpEchoRequest(ip1);
        assertTrue(tap2.send(sent));
        helper1_3.checkIcmpEchoRequest(sent,tap1.recv());

        sent = helper1_3.makeIcmpEchoRequest(ip3);
        assertTrue(tap1.send(sent));
        helper3_1.checkIcmpEchoRequest(sent, tap2.recv());

        assertNull(tap3.recv());
        assertNull(tap1.recv());

    }
}
