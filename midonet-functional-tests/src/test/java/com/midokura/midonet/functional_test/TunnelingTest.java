package com.midokura.midonet.functional_test;

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
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.MidoPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;

public class TunnelingTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory
            .getLogger(TunnelingTest.class);

    static Tenant tenant1;
    static TapWrapper tapPort1;
    static TapWrapper tapPort2;
    static IntIPv4 ip1;
    static IntIPv4 ip2;
    static PacketHelper helper1;
    static PacketHelper helper2;
    static OpenvSwitchDatabaseConnection ovsdb;
    static MidolmanMgmt mgmt;
    static MidolmanLauncher midolman;
    static OvsBridge ovsBridge1;
    static OvsBridge ovsBridge2;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman = MidolmanLauncher.start();

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        if (ovsdb.hasBridge("smoke-br2"))
            ovsdb.delBridge("smoke-br2");

        ovsBridge1 = new OvsBridge(ovsdb, "smoke-br", OvsBridge.L3UUID);
        ovsBridge2 = new OvsBridge(ovsdb, "smoke-br2", OvsBridge.L3UUID,
                "tcp:127.0.0.1:6657");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant").build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();

        ip1 = IntIPv4.fromString("192.168.231.2");
        MidoPort p1 = router1.addVmPort().setVMAddress(ip1).build();
        tapPort1 = new TapWrapper("tnlTestTap1");
        ovsBridge1.addSystemPort(p1.port.getId(), tapPort1.getName());

        helper1 = new PacketHelper(MAC.fromString("02:00:aa:33:00:01"), ip1,
                tapPort1.getHwAddr(), IntIPv4.fromString("192.168.231.1"));

        ip2 = IntIPv4.fromString("192.168.231.3");
        MidoPort p2 = router1.addVmPort().setVMAddress(ip2).build();
        tapPort2 = new TapWrapper("tnlTestTap2");
        ovsBridge2.addSystemPort(p2.port.getId(), tapPort2.getName());

        helper2 = new PacketHelper(MAC.fromString("02:00:aa:33:00:02"), ip2,
                tapPort2.getHwAddr(), IntIPv4.fromString("192.168.231.1"));

        Thread.sleep(5 * 1000);
    }

    @AfterClass
    public static void tearDown() throws InterruptedException {
        stopMidolman(midolman);
        removeTenant(tenant1);
        stopMidolmanMgmt(mgmt);
        ovsBridge1.remove();
        ovsBridge2.remove();
        removeTapWrapper(tapPort1);
        removeTapWrapper(tapPort2);
    }

    @Test
    public void testPingTunnel() {
        byte[] sent;

        // First arp for router's mac from port1.
        assertTrue(tapPort1.send(helper1.makeArpRequest()));
        helper1.checkArpReply(tapPort1.recv());

        // Arp for router's mac from port2.
        assertTrue(tapPort2.send(helper2.makeArpRequest()));
        helper2.checkArpReply(tapPort2.recv());

        // Now try sending an ICMP over the tunnel.
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
