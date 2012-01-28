package com.midokura.midonet.functional_test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

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
import com.midokura.midolman.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.MidoPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Default;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Without_Bgp;

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
    static MidolmanLauncher midolman1;
    static MidolmanLauncher midolman2;
    static OvsBridge ovsBridge1;
    static OvsBridge ovsBridge2;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman1 = MidolmanLauncher.start(Default, "TunnelingTest-smoke_br");
        midolman2 = MidolmanLauncher.start(Without_Bgp, "TunnelingTest-smoke_br2");

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        if (ovsdb.hasBridge("smoke-br2"))
            ovsdb.delBridge("smoke-br2");

        ovsBridge1 = new OvsBridge(ovsdb, "smoke-br", OvsBridge.L3UUID);
        ovsBridge2 = new OvsBridge(ovsdb, "smoke-br2", OvsBridge.L3UUID,
                "tcp:127.0.0.1:6657");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant-tunneling").build();
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
        removeTapWrapper(tapPort1);
        removeTapWrapper(tapPort2);
        removeBridge(ovsBridge1);
        removeBridge(ovsBridge2);
        stopMidolman(midolman1);
        stopMidolman(midolman2);
        removeTenant(tenant1);
        stopMidolmanMgmt(mgmt);
    }

    @Test
    public void testPingTunnel() throws MalformedPacketException {

        // First arp for router's mac from port1.
        assertThat("An ARP packet was successfully sent via the first tap port",
                   tapPort1.send(helper1.makeArpRequest()));
        helper1.checkArpReply(tapPort1.recv());

        // Arp for router's mac from port2.
        assertThat(
            "An ARP packet was successfully sent via the second tap port",
            tapPort2.send(helper2.makeArpRequest()));
        helper2.checkArpReply(tapPort2.recv());

        byte[] icmpRequest;

        // Now try sending an ICMP over the tunnel.
        icmpRequest = helper1.makeIcmpEchoRequest(ip2);
        assertThat("An ICMP request was sent via the first port",
                   tapPort1.send(icmpRequest));

        // Note: the virtual router ARPs before delivering the IPv4 packet.
        helper2.checkArpRequest(tapPort2.recv());
        assertThat("An ARP reply was properly sent to the second tap port",
                   tapPort2.send(helper2.makeArpReply()));

        // receive the icmp
        helper2.checkIcmpEchoRequest(icmpRequest, tapPort2.recv());

        icmpRequest = helper2.makeIcmpEchoRequest(ip1);
        assertThat("An ICMP request was sent via the second port",
                   tapPort2.send(icmpRequest));

        // Note: the virtual router ARPs before delivering the IPv4 packet.
        helper1.checkArpRequest(tapPort1.recv());
        assertThat("An ARP reply was sent to the first tap port",
                   tapPort1.send(helper1.makeArpReply()));

        // receive the icmp
        helper1.checkIcmpEchoRequest(icmpRequest, tapPort1.recv());

        assertThat("No more packages arrived on the first tap port",
                   tapPort1.recv(), is(nullValue()));
        assertThat("No more packages arrived on the second tap port",
                   tapPort1.recv(), is(nullValue()));
    }
}
