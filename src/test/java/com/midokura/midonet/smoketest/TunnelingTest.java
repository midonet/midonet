package com.midokura.midonet.smoketest;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.TapPort;
import com.midokura.midonet.smoketest.topology.Tenant;

public class TunnelingTest {

	private final static Logger log = LoggerFactory
            .getLogger(SmokeTest2.class);
    static Tenant tenant1;
    static TapPort tapPort1;
    static TapPort tapPort2;
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
        //Process p = Runtime.getRuntime().exec(
        //        "sudo -n ip tuntap del dev tapPort1 mode tap");
        //p.waitFor();
        //p = Runtime.getRuntime().exec(
        //        "sudo -n ip tuntap del dev tapPort2 mode tap");
        //p.waitFor();
        if(ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        if(ovsdb.hasBridge("smoke-br2"))
            ovsdb.delBridge("smoke-br2");
        String tenantName = "tenant" + rand.nextInt();
        tenant1 = new Tenant.Builder(mgmt).setName(tenantName).build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();
        tapPort1 = router1.addPort(ovsdb).setDestination("192.168.100.2")
        		.setOVSBridgeName("smoke-br")
        		.setOVSBridgeController("tcp:127.0.0.1:6623")
        		.buildTap();
        helper1 = new PacketHelper(tapPort1.getInnerMAC(), "192.168.100.2");
        tapPort2 = router1.addPort(ovsdb).setDestination("192.168.101.3").setOVSBridgeName("smoke-br2").buildTap();
        helper2 = new PacketHelper(tapPort2.getInnerMAC(), "192.168.101.3");

        Thread.sleep(1000);
    }

    @AfterClass
    public static void tearDown() {
        //ovsdb.delBridge("smoke-br");
        /*
        rtrLink.delete();
        DtoTenant[] tenants = mgmt.getTenants();
        for (int i = 0; i < tenants.length; i++)
            mgmt.delete(tenants[i].getUri());
        */
        //tenant1.delete();
        //tenant2.delete();
    }

    @Test
    public void testPingTunnel() {
        /* Ping my router's port, then ping the peer port. */

        // Note: the router port's own MAC is the tap's hwAddr.
        IntIPv4 ip1 = IntIPv4.fromString("192.168.100.2");
        IntIPv4 ip2 = IntIPv4.fromString("192.168.101.3");
        IntIPv4 rtrIp2 = IntIPv4.fromString("192.168.101.1");
        IntIPv4 rtrIp1 = IntIPv4.fromString("192.168.100.1");
        
        byte[] sent;
        byte[] received;

        sent = helper1.makeIcmpEchoRequest(tapPort1.getOuterMAC(), ip2);
        assertTrue(tapPort1.send(sent));
        // Note: the virtual router ARPs before delivering the IPv4 packet.
        received = tapPort2.recv();
        helper2.checkArpRequest(received, tapPort2.getOuterMAC(), rtrIp2);
        assertTrue(tapPort2.send(helper2.makeArpReply(tapPort2.getOuterMAC(), rtrIp2)));
        // receive the icmp
        received = tapPort2.recv();
        Assert.assertEquals(sent.length, received.length);
        sent = helper2.makeIcmpEchoRequest(tapPort2.getOuterMAC(), ip1);
        assertTrue(tapPort2.send(sent));
        // Note: the virtual router ARPs before delivering the IPv4 packet.
        received = tapPort1.recv();
        helper1.checkArpRequest(received, tapPort1.getOuterMAC(), rtrIp1);
        assertTrue(tapPort1.send(helper1.makeArpReply(tapPort1.getOuterMAC(), rtrIp1)));
        received = tapPort1.recv();
        Assert.assertEquals(sent.length, received.length);
        Arrays.fill(received, 0, 12, (byte)0);
        Arrays.fill(sent, 0, 12, (byte)0);
        Assert.assertArrayEquals(sent, received);

        assertNull(tapPort2.recv());
    }


}
