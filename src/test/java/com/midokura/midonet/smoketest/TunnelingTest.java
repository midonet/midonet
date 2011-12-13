package com.midokura.midonet.smoketest;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Random;

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

public class TunnelingTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory.getLogger(TunnelingTest.class);

    static Tenant tenant1;
    static TapPort tapPort1;
    static TapPort tapPort2;
    static IntIPv4 ip1;
    static IntIPv4 ip2;
    static PacketHelper helper1;
    static PacketHelper helper2;
    static OpenvSwitchDatabaseConnection ovsdb;
    static MidolmanMgmt mgmt;

    static Random rand = new Random(System.currentTimeMillis());

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                         "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        // First clean up left-overs from previous incomplete tests.
        // Process p = Runtime.getRuntime().exec(
        // "sudo -n ip tuntap del dev tapPort1 mode tap");
        // p.waitFor();
        // p = Runtime.getRuntime().exec(
        // "sudo -n ip tuntap del dev tapPort2 mode tap");
        // p.waitFor();
        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        if (ovsdb.hasBridge("smoke-br2"))
            ovsdb.delBridge("smoke-br2");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant" + rand.nextInt())
                      .build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();

        ip1 = IntIPv4.fromString("192.168.100.2");
        tapPort1 = router1.addPort(ovsdb)
                       .setDestination(ip1.toString())
                       .setOVSPortName("tapPort1")
                       .buildTap();

        helper1 = new PacketHelper(tapPort1.getInnerMAC(), ip1,
                                      tapPort1.getOuterMAC(),
                                      IntIPv4.fromString("192.168.100.1"));

        ip2 = IntIPv4.fromString("192.168.101.3");
        tapPort2 = router1.addPort(ovsdb).setDestination(ip2.toString())
                       .setOVSPortName("tapPort2")
                       .setOVSBridgeName("smoke-br2")
                       .setOVSBridgeController("tcp:127.0.0.1:6623")
                       .buildTap();

        helper2 = new PacketHelper(tapPort2.getInnerMAC(), ip2,
                                      tapPort2.getOuterMAC(),
                                      IntIPv4.fromString("192.168.101.1"));

        Thread.sleep(1000);
    }

    @AfterClass
    public static void tearDown() {
        ovsdb.delBridge("smoke-br");
        ovsdb.delBridge("smoke-br2");

        removePort(tapPort1);
        removePort(tapPort2);
        removeTenant(tenant1);

        mgmt.stop();

        resetZooKeeperState(log);
    }

    @Test
    public void testPingTunnel() {
        byte[] sent;

        sent = helper1.makeIcmpEchoRequest(ip2);
        assertTrue(tapPort1.send(sent));
        // Note: the virtual router ARPs before delivering the IPv4 packet.
        helper2.checkArpRequest(tapPort2.recv());
        assertTrue(tapPort2.send(helper2.makeArpReply()));
        // receive the icmp
        helper2.checkIcmpEchoRequest(sent, tapPort2.recv());

        sent = helper2.makeIcmpEchoRequest(ip1);
        assertTrue(tapPort2.send(sent));
        // Note: the virtual router ARPs before delivering the IPv4 packet.
        helper1.checkArpRequest(tapPort1.recv());
        assertTrue(tapPort1.send(helper1.makeArpReply()));
        // receive the icmp
        helper1.checkIcmpEchoRequest(sent, tapPort1.recv());

        assertNull(tapPort1.recv());
        assertNull(tapPort2.recv());
    }
}
