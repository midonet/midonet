package com.midokura.midonet.functional_test;

import java.io.IOException;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.openflow.ServiceController;
import com.midokura.midonet.functional_test.topology.MaterializedRouterPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.lock.LockHelper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.assertNoMorePacketsOnTap;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.sleepBecause;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolmanMgmt;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.waitForBridgeToConnect;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Default;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Without_Bgp;

@Ignore
public class TunnelingTest {

    private final static Logger log = LoggerFactory
            .getLogger(TunnelingTest.class);

    Tenant tenant1;
    TapWrapper tapPort1;
    TapWrapper tapPort2;
    IntIPv4 ip1;
    IntIPv4 ip2;
    PacketHelper helper1;
    PacketHelper helper2;
    OpenvSwitchDatabaseConnection ovsdb;
    MidolmanMgmt mgmt;
    MidolmanLauncher midolman1;
    MidolmanLauncher midolman2;
    OvsBridge ovsBridge1;
    OvsBridge ovsBridge2;

    static LockHelper.Lock lock;

    @BeforeClass
    public static void checkLock() {
        lock = LockHelper.lock(FunctionalTestsHelper.LOCK_NAME);
    }

    @AfterClass
    public static void releaseLock() {
        lock.release();
    }

    @Before
    public void setUp() throws Exception, IOException {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman1 = MidolmanLauncher.start(Default, "TunnelingTest-smoke_br");
        midolman2 = MidolmanLauncher.start(Without_Bgp, "TunnelingTest-smoke_br2");

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        if (ovsdb.hasBridge("smoke-br2"))
            ovsdb.delBridge("smoke-br2");

        ovsBridge1 = new OvsBridge(ovsdb, "smoke-br");
        ovsBridge2 = new OvsBridge(ovsdb, "smoke-br2", "tcp:127.0.0.1:6657");
        ovsBridge1.addServiceController(6640);
        waitForBridgeToConnect(new ServiceController(6640));

        tenant1 = new Tenant.Builder(mgmt).setName("tenant-tunneling").build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();

        ip1 = IntIPv4.fromString("192.168.231.2");
        MaterializedRouterPort p1 = router1.addVmPort().setVMAddress(ip1).build();
        tapPort1 = new TapWrapper("tnlTestTap1");
        ovsBridge1.addSystemPort(p1.port.getId(), tapPort1.getName());

        helper1 = new PacketHelper(MAC.fromString("02:00:aa:33:00:01"), ip1,
                IntIPv4.fromString("192.168.231.1"));

        ip2 = IntIPv4.fromString("192.168.231.3");
        MaterializedRouterPort p2 = router1.addVmPort().setVMAddress(ip2).build();
        tapPort2 = new TapWrapper("tnlTestTap2");
        ovsBridge2.addSystemPort(p2.port.getId(), tapPort2.getName());

        helper2 = new PacketHelper(MAC.fromString("02:00:aa:33:00:02"), ip2,
                IntIPv4.fromString("192.168.231.1"));
        sleepBecause("wait for the network config to settle", 15);
    }

    @After
    public void tearDown() throws InterruptedException {
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
        MAC gwMac = helper1.checkArpReply(tapPort1.recv());
        helper1.setGwMac(gwMac);

        // Arp for router's mac from port2.
        assertThat(
                "An ARP packet was successfully sent via the second tap port",
                tapPort2.send(helper2.makeArpRequest()));
        gwMac = helper2.checkArpReply(tapPort2.recv());
        helper2.setGwMac(gwMac);

        byte[] icmpRequest;

        // Now try sending an ICMP over the tunnel.
        icmpRequest = helper1.makeIcmpEchoRequest(ip2);
        assertThat("An ICMP request was sent via the first port",
                tapPort1.send(icmpRequest));

        // Note: the virtual router ARPs before delivering the IPv4 packet.
        helper2.checkArpRequest(tapPort2.recv());
        assertThat("An ARP reply was properly sent to the second tap port",
                   tapPort2.send(helper2.makeArpReply()));

        // receive the icmp. It first arrives at the proxy.
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

        assertNoMorePacketsOnTap(tapPort1);
        assertNoMorePacketsOnTap(tapPort2);
    }

}
