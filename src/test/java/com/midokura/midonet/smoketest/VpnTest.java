/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.state.VpnZkManager.VpnType;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.*;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VpnTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory.getLogger(VpnTest.class);

    static Tenant tenant1;
    static TapPort tapPort1;
    static TapPort tapPort2;
    static PeerRouterLink link;
    static OpenvSwitchDatabaseConnection ovsdb;
    static MidolmanMgmt mgmt;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        
        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        
        Thread.sleep(2000);

        Random rand = new Random(System.currentTimeMillis());
        tenant1 = new Tenant.Builder(mgmt).setName("tenant" + rand.nextInt())
                .build();

        // Router 1 has VMs on 10.0.231.0/24.
        Router router1 = tenant1.addRouter().setName("rtr1").build();
        // Here's a VM on router1.
        tapPort1 = router1.addPort(ovsdb).setDestination("10.0.231.11")
                .buildTap();

        // Router 2 has VMs on 10.0.232.0/24.
        Router router2 = tenant1.addRouter().setName("rtr2").build();
        // Here's a VM on router2.
        tapPort2 = router2.addPort(ovsdb).setDestination("10.0.232.4")
                .setOVSPortName("tapPort2")
                .buildTap();

        // Link the two routers. Only "public" addresses should traverse the
        // link between the routers. Router 1 owns public addresses in
        // 192.168.231.0/24, and router 2 has addresses in 192.168.232.0/24.
        link = router1.addRouterLink().setPeer(router2)
                .setLocalPrefix("192.168.231.0").setPeerPrefix("192.168.232.0")
                .build();

        // Router 1 has a port that leads to 10.0.232.0/24 via a VPN
        // and gateway (router2).
        // p1 private port of the VPN
        MidoPort p1 = router1
                .addGwPort()
                .setLocalLink(IntIPv4.fromString("169.254.0.1"),
                        IntIPv4.fromString("169.254.0.2"))  // virtual path created by the vpn
                .addRoute(IntIPv4.fromString("10.0.232.0")).build();
        MidoPort vpn1 = router1.addVpnPort()
                .setVpnType(VpnType.OPENVPN_TCP_CLIENT)
                .setLocalIp(IntIPv4.fromString("10.0.231.100"))
                .setLayer4Port(12333)
                .setRemoteIp("192.168.232.99")
                .setPrivatePortId(p1.port.getId()).build();

        router1.addFloatingIp(IntIPv4.fromString("10.0.231.100"),
                IntIPv4.fromString("192.168.231.100"), link.dto.getPortId());

        // Router 2 has a port that leads to 10.0.231.0/24 via a VPN
        // and gateway (router2).
        MidoPort p2 = router2
                .addGwPort()
                .setLocalLink(IntIPv4.fromString("169.254.0.2"),
                        IntIPv4.fromString("169.254.0.1"))
                .addRoute(IntIPv4.fromString("10.0.231.0")).build();
        MidoPort vpn2 = router2.addVpnPort()
                .setVpnType(VpnType.OPENVPN_TCP_SERVER)
                .setLocalIp(IntIPv4.fromString("10.0.232.99"))
                .setLayer4Port(12333)
                .setPrivatePortId(p2.port.getId()).build();
        router2.addFloatingIp(IntIPv4.fromString("10.0.232.99"),
                IntIPv4.fromString("192.168.232.99"), link.dto.getPeerPortId());

        Thread.sleep(20000);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        removePort(tapPort1);
        removePort(tapPort2);
        removeTenant(tenant1);

        mgmt.stop();

        resetZooKeeperState(log);
    }

    @Test
    public void testPingOverVPN() {
        IntIPv4 ip1 = IntIPv4.fromString("10.0.231.11");
        IntIPv4 rtr1 = IntIPv4.fromString("10.0.231.1");
        IntIPv4 ip2 = IntIPv4.fromString("10.0.232.4");
        IntIPv4 rtr2 = IntIPv4.fromString("10.0.232.1");
        PacketHelper helper1 = new PacketHelper(tapPort1.getInnerMAC(), ip1,
                tapPort1.getOuterMAC(), rtr1);
        PacketHelper helper2 = new PacketHelper(tapPort2.getInnerMAC(), ip2,
                tapPort2.getOuterMAC(), rtr2);
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
