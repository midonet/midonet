/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.assertTrue;

import com.midokura.midolman.mgmt.data.dto.client.DtoVpn.VpnType;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.RouterPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.PeerRouterLink;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.assertNoMorePacketsOnTap;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeVpn;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.sleepBecause;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolmanMgmt;

public class VpnTest {

    private final static Logger log = LoggerFactory.getLogger(VpnTest.class);

    Tenant tenant1;
    TapWrapper tapPort1;
    TapWrapper tapPort2;
    PeerRouterLink link;
    OpenvSwitchDatabaseConnection ovsdb;
    MidolmanMgmt mgmt;
    MidolmanLauncher midolman;
    OvsBridge ovsBridge;
    RouterPort vpn1;
    RouterPort vpn2;

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
        RouterPort p = router1.addVmPort().setVMAddress(ip1).build();
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
        link = router1.addRouterLink().setPeer(router2)
                .setLocalPrefix("192.168.231.0").setPeerPrefix("192.168.232.0")
                .build();

        // Router 1 has a port that leads to 10.0.232.0/24 via a VPN
        // and gateway (router2).
        // p1 private port of the VPN
        RouterPort p1 = router1
                .addGwPort()
                .setLocalLink(IntIPv4.fromString("169.254.0.1"),
                        IntIPv4.fromString("169.254.0.2"))
                .addRoute(IntIPv4.fromString("10.0.232.0")).build();
        vpn1 = router1.addVpnPort()
                .setVpnType(VpnType.OPENVPN_TCP_CLIENT)
                .setLocalIp(IntIPv4.fromString("10.0.231.100"))
                .setLayer4Port(12333).setRemoteIp("192.168.232.99")
                .setPrivatePortId(p1.port.getId()).build();

        router1.addFloatingIp(IntIPv4.fromString("10.0.231.100"),
                IntIPv4.fromString("192.168.231.100"), link.dto.getPortId());

        // Router 2 has a port that leads to 10.0.231.0/24 via a VPN
        // and gateway (router2).
        RouterPort p2 = router2
                .addGwPort()
                .setLocalLink(IntIPv4.fromString("169.254.0.2"),
                        IntIPv4.fromString("169.254.0.1"))
                .addRoute(IntIPv4.fromString("10.0.231.0")).build();
        vpn2 = router2.addVpnPort()
                .setVpnType(VpnType.OPENVPN_TCP_SERVER)
                .setLocalIp(IntIPv4.fromString("10.0.232.99"))
                .setLayer4Port(12333).setPrivatePortId(p2.port.getId()).build();
        router2.addFloatingIp(IntIPv4.fromString("10.0.232.99"),
                IntIPv4.fromString("192.168.232.99"), link.dto.getPeerPortId());

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
        if (null != link)
            link.delete();
        removeTenant(tenant1);
        stopMidolmanMgmt(mgmt);
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
