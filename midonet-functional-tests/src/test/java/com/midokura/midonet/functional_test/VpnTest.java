/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.IOException;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import static com.midokura.util.Waiters.sleepBecause;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import com.midokura.midonet.client.dto.DtoVpn.VpnType;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.MaterializedRouterPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.LogicalRouterPort;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.lock.LockHelper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.assertNoMorePacketsOnTap;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeVpn;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;

public class VpnTest {

    private final static Logger log = LoggerFactory.getLogger(VpnTest.class);

    Tenant tenant1;
    TapWrapper tapPort1;
    TapWrapper tapPort2;
    OpenvSwitchDatabaseConnection ovsdb;
    MidolmanMgmt mgmt;
    MidolmanLauncher midolman;
    OvsBridge ovsBridge;
    MaterializedRouterPort vpn1;
    MaterializedRouterPort vpn2;
    LogicalRouterPort uplinkPort;

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
    public void setUp() throws InterruptedException, IOException {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman = MidolmanLauncher.start("VpnTest");

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        ovsBridge = new OvsBridge(ovsdb, "smoke-br");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant-vpn").build();

        // Router 1 has VMs on 10.0.231.0/24.
        Router router1 = tenant1.addRouter().setName("rtr1").build();
        // Here's a VM on router1.
        IntIPv4 ip1 = IntIPv4.fromString("10.0.231.11");
        MaterializedRouterPort p = router1.addVmPort().setVMAddress(ip1).build();
        tapPort1 = new TapWrapper("vpnTestTap1");
        ovsBridge.addSystemPort(p.port.getId(), tapPort1.getName());

        // Router 2 has VMs on 10.0.232.0/24.
        Router router2 = tenant1.addRouter().setName("rtr2").build();
        // Here's a VM on router2.
        IntIPv4 ip2 = IntIPv4.fromString("10.0.232.4");
        p = router1.addVmPort().setVMAddress(ip2).build();
        tapPort2 = new TapWrapper("vpnTestTap2");
        ovsBridge.addSystemPort(p.port.getId(), tapPort2.getName());

        // Link the two routers. Only "public" addresses should traverse the
        // link between the routers. Router 1 owns public addresses in
        // 192.168.231.0/24, and router 2 has addresses in 192.168.232.0/24.
        uplinkPort = router1.addLinkPort()
                .setNetworkAddress("169.254.1.0").setNetworkLength(30)
                .setPortAddress("169.254.1.1").build();
        LogicalRouterPort router2port1 = router2.addLinkPort()
                .setNetworkAddress("169.254.1.0").setNetworkLength(30)
                .setPortAddress("169.254.1.2").build();
        uplinkPort.link(router2port1, "192.168.231.0", "192.168.232.0");

        // Router 1 has a port that leads to 10.0.232.0/24 via a VPN
        // and gateway (router2).
        // p1 private port of the VPN
        MaterializedRouterPort p1 = router1
                .addGwPort()
                .setLocalLink(IntIPv4.fromString("169.254.0.1"),
                        IntIPv4.fromString("169.254.0.2"))
                .addRoute(IntIPv4.fromString("10.0.232.0", 24)).build();
        vpn1 = router1.addVpnPort()
                .setVpnType(VpnType.OPENVPN_TCP_CLIENT)
                .setLocalIp(IntIPv4.fromString("10.0.231.100"))
                .setLayer4Port(12333).setRemoteIp("192.168.232.99")
                .setPrivatePortId(p1.port.getId()).build();

        router1.addFilters();
        router1.addFloatingIp(IntIPv4.fromString("10.0.231.100"),
                IntIPv4.fromString("192.168.231.100"),
                uplinkPort.port.getId());

        // Router 2 has a port that leads to 10.0.231.0/24 via a VPN
        // and gateway (router2).
        MaterializedRouterPort p2 = router2
                .addGwPort()
                .setLocalLink(IntIPv4.fromString("169.254.0.2"),
                        IntIPv4.fromString("169.254.0.1"))
                .addRoute(IntIPv4.fromString("10.0.231.0", 24)).build();
        vpn2 = router2.addVpnPort()
                .setVpnType(VpnType.OPENVPN_TCP_SERVER)
                .setLocalIp(IntIPv4.fromString("10.0.232.99"))
                .setLayer4Port(12333).setPrivatePortId(p2.port.getId()).build();
        router2.addFilters();
        router2.addFloatingIp(IntIPv4.fromString("10.0.232.99"),
                IntIPv4.fromString("192.168.232.99"),
                router2port1.port.getId());

        sleepBecause("wait for the network config to settle", 5);
    }

    @After
    public void tearDown() throws Exception {
        // delete vpns
        removeTapWrapper(tapPort1);
        removeTapWrapper(tapPort2);
        removeVpn(mgmt, vpn1);
        removeVpn(mgmt, vpn2);
        removeBridge(ovsBridge);
        // TODO: Convert the following to a condition.
        // Give some second to the controller to clean up vpns stuff
        sleepBecause("wait for midolman to clean vpn configuration up", 10);
        stopMidolman(midolman);
        if (null != uplinkPort)
            uplinkPort.unlink();
        removeTenant(tenant1);
   //     stopMidolmanMgmt(mgmt);
    }

    @Test
    public void testPingOverVPN() throws MalformedPacketException {
        IntIPv4 ip1 = IntIPv4.fromString("10.0.231.11");
        IntIPv4 rtr1 = IntIPv4.fromString("10.0.231.1");
        IntIPv4 ip2 = IntIPv4.fromString("10.0.232.4");
        IntIPv4 rtr2 = IntIPv4.fromString("10.0.232.1");
        PacketHelper helper1 = new PacketHelper(
                MAC.fromString("02:00:00:dd:dd:01"), ip1, rtr1);
        PacketHelper helper2 = new PacketHelper(
                MAC.fromString("02:00:00:dd:dd:02"), ip2, rtr2);

        // First arp for router1's mac.
        assertThat("The ARP request was sent properly",
                tapPort1.send(helper1.makeArpRequest()));
        MAC rtrMac = helper1.checkArpReply(tapPort1.recv());
        helper1.setGwMac(rtrMac);

        // First arp for router2's mac.
        assertThat("The ARP request was sent properly",
                tapPort2.send(helper2.makeArpRequest()));
        rtrMac = helper2.checkArpReply(tapPort2.recv());
        helper2.setGwMac(rtrMac);

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


        assertNoMorePacketsOnTap(tapPort1);
        assertNoMorePacketsOnTap(tapPort2);
    }
}
