package com.midokura.midonet.smoketest;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Random;

import org.junit.AfterClass;
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
import com.midokura.midonet.smoketest.topology.InternalPort;
import com.midokura.midonet.smoketest.topology.PeerRouterLink;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.TapPort;
import com.midokura.midonet.smoketest.topology.Tenant;

public class TunnelingTest {

	private final static Logger log = LoggerFactory
            .getLogger(SmokeTest2.class);
    static Tenant tenant1;
    static Tenant tenant2;
    static PeerRouterLink rtrLink;
    static TapPort tapPort;
    static InternalPort internalPort1;
    static InternalPort internalPort2;
    static OpenvSwitchDatabaseConnection ovsdb;
    static PacketHelper helper;
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
        if(ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        if(ovsdb.hasBridge("smoke-br2"))
            ovsdb.delBridge("smoke-br2");
        /*
        try {
            mgmt.deleteTenant("tenant30");
            log.debug("deleted tenant30");
        }
        catch (Exception e) {
        	log.error("failed to delete tenant30", e);
        }
        */
        String tenantName = "tenant" + rand.nextInt();
        tenant1 = new Tenant.Builder(mgmt).setName(tenantName).build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();
        tapPort = router1.addPort(ovsdb).setDestination("192.168.100.2")
                .buildTap();
        helper = new PacketHelper(tapPort.getInnerMAC(), "192.168.100.2");
        internalPort1 = router1.addPort(ovsdb).setDestination("192.168.101.3").setOVSBridgeName("smoke-br2").setOVSBridgeController("tcp:127.0.0.1:6623")
                .buildInternal();

        Process mmController = Runtime.getRuntime()
                .exec("sudo /usr/lib/jvm/java-6-openjdk/jre/bin/java "
                        + "-cp ./conf:/usr/share/midolman/midolmanj.jar "
                        + "-Dmidolman.log.dir=. "
                        + "-Dcom.sun.management.jmxremote "
                        + "-Dcom.sun.management.jmxremote.local.only= "
                        + "com.midokura.midolman.Midolman "
                        + "-c ./conf/midolman2.conf");
       /*
        tenant2 = new Tenant.Builder(mgmt).setName("tenant" + rand.nextInt()).build();
        Router router2 = tenant2.addRouter().setName("rtr2").build();
        internalPort2 = router2.addPort(ovsdb).setDestination("192.168.101.2")
                .buildInternal();

        rtrLink = router1.addRouterLink().setPeer(router2).
                setLocalPrefix("192.168.100.0").setPeerPrefix("192.168.100.0").
                build();*/
        

        Thread.sleep(120000);
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
        MAC rtrMac = tapPort.getOuterMAC();
        IntIPv4 rtrIp = IntIPv4.fromString("192.168.100.1");

        byte[] request;
        byte[] reply;

        // First arp for router's mac.
        request = helper.makeArpRequest(rtrIp);
        assertTrue(tapPort.send(request));
        reply = tapPort.recv();
        helper.checkArpReply(reply, rtrMac, rtrIp);

        // Ping router's port.
        request = helper.makeIcmpEchoRequest(rtrMac, rtrIp);
        assertTrue(tapPort.send(request));
        // Note: Midolman's virtual router currently does not ARP before
        // responding to ICMP echo requests addressed to its own port.
        helper.checkIcmpEchoReply(request, tapPort.recv());

        // Ping peer port.
        IntIPv4 peerIp = IntIPv4.fromString("192.168.101.3");
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
