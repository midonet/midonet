/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.LogicalRouterPort;
import com.midokura.midonet.functional_test.topology.MaterializedRouterPort;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.Tenant;

public class ConfigTearDownTest {

    private final static Logger log = LoggerFactory
            .getLogger(ConfigTearDownTest.class);

    static MidolmanMgmt mgmt;

    @BeforeClass
    public static void setUp() {
        mgmt = new MockMidolmanMgmt(true);
    }

    @AfterClass
    public static void tearDown() {
        if (null != mgmt)
            mgmt.stop();
    }

    @Test
    public void test1() {
        Tenant t = new Tenant.Builder(mgmt).setName("tenant-config-1")
                .build();
        t.addRouter().setName("rtr1").build();
        t.delete();
    }

    @Test
    public void test2() {
        IntIPv4 ip1 = IntIPv4.fromString("192.168.231.2");
        IntIPv4 ip2 = IntIPv4.fromString("192.168.231.3");
        IntIPv4 ip3 = IntIPv4.fromString("192.168.231.4");

        Tenant t = new Tenant.Builder(mgmt).setName("tenant-config-2").build();
        Router rtr = t.addRouter().setName("rtr1").build();

        MaterializedRouterPort p1 = rtr.addVmPort().setVMAddress(ip1).build();
        rtr.addVmPort().setVMAddress(ip2).build();
        rtr.addVmPort().setVMAddress(ip3).build();

        p1.delete();
        t.delete();
    }

    @Test
    public void test3() {
        Tenant tenant1 = new Tenant.Builder(mgmt).setName("tenant-config-3").build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();

        IntIPv4 tapAddr1 = IntIPv4.fromString("192.168.66.2");
        MaterializedRouterPort p1 = router1.addVmPort().setVMAddress(tapAddr1).build();

        IntIPv4 tapAddr2 = IntIPv4.fromString("192.168.66.3");
        MaterializedRouterPort p2 = router1.addVmPort().setVMAddress(tapAddr2).build();

        // The internal port has private address 192.168.55.5; floating ip
        // 10.0.173.5 is mapped to 192.168.55.5. Treat tapPort1 as the uplink:
        // only packets that go via the uplink use the the floatingIP.
        IntIPv4 privAddr = IntIPv4.fromString("192.168.55.5");
        IntIPv4 pubAddr = IntIPv4.fromString("10.0.173.5");
        MaterializedRouterPort p3 = router1.addVmPort().setVMAddress(privAddr).build();
        router1.addFloatingIp(privAddr, pubAddr, p1.port.getId());

        tenant1.delete();
    }

    @Test
    public void test4() {

        Tenant tenant1 = new Tenant.Builder(mgmt).setName("tenant-config-4").build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();
        Router router2 = tenant1.addRouter().setName("rtr2").build();

        // Link the two routers.
        LogicalRouterPort router1port1 = router1.addLinkPort()
                .setNetworkAddress("169.254.1.0").setNetworkLength(30)
                .setPortAddress("169.254.1.1").build();
        LogicalRouterPort router2port1 = router2.addLinkPort()
                .setNetworkAddress("169.254.1.0").setNetworkLength(30)
                .setPortAddress("169.254.1.2").build();

        router1port1.link(router2port1, "192.168.231.0", "192.168.232.0");
        router1port1.unlink();

        tenant1.delete();
    }
}
