package com.midokura.midonet.smoketest;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.InterRouterLink;
import com.midokura.midonet.smoketest.topology.InternalPort;
import com.midokura.midonet.smoketest.topology.Port;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.TapPort;
import com.midokura.midonet.smoketest.topology.Tenant;

public class SmokeTest2 {

    static Tenant tenant1;
    static Tenant tenant2;
    static InterRouterLink rtrLink;
    static TapPort tapPort;
    static InternalPort internalPort;
    static OpenvSwitchDatabaseConnection ovsdb;

    @BeforeClass
    public static void setUp() {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        MockMidolmanMgmt mgmt = new MockMidolmanMgmt(true);
        tenant1 = new Tenant.Builder(mgmt).setName("tenant1").build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();
        tapPort = router1.addPort(ovsdb).setDestination("192.168.100.2")
                .buildTap();

        tenant2 = new Tenant.Builder(mgmt).setName("tenant2").build();
        Router router2 = tenant2.addRouter().setName("rtr1").build();
        internalPort = router2.addPort(ovsdb).setDestination("192.168.101.2")
                .buildInternal();

        rtrLink = router1.addRouterLink().setLocalAddress("10.100.1.1")
                .setPeer(router2).setPeerAddress("10.100.1.2")
                .setLinkAddressLength(30).build();

        Port linkPort1 = rtrLink.getLocalPort();
        linkPort1.addRoute().setDestination("192.168.101.0")
                .setDestinationLength(24).build();
        Port linkPort2 = rtrLink.getPeerPort();
        linkPort2.addRoute().setDestination("192.168.100.0")
                .setDestinationLength(24).build();
    }

    @AfterClass
    public static void tearDown() {
        rtrLink.delete();
        tenant1.delete();
        tenant2.delete();
        ovsdb.delBridge("smoke-br");
    }

    @Test
    public void testDhcpClient() {

    }

    @Test
    public void testPingTapToInternal() {
        tapPort.sendICMP("192.168.100.1");
    }
}
