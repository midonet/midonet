/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.functional_test;

import org.junit.Ignore;
import org.junit.Test;
import org.midonet.packets.IPv4Addr;

@Ignore
public class ConfigTearDownTest extends TestBase {

    @Override
    protected void setup() {}

    @Override
    protected void teardown() {}

    @Test
    public void test1() {
        /*Tenant t = new Tenant.Builder(mgmt).setName("tenant-config-1")
                .build();
        t.addRouter().setName("rtr1").build();
        t.delete();*/
    }

    @Test
    public void test2() {
        IPv4Addr ip1 = IPv4Addr.fromString("192.168.231.2");
        IPv4Addr ip2 = IPv4Addr.fromString("192.168.231.3");
        IPv4Addr ip3 = IPv4Addr.fromString("192.168.231.4");

        /*Tenant t = new Tenant.Builder(mgmt).setName("tenant-config-2").build
            ();
        Router rtr = t.addRouter().setName("rtr1").build();

        ExteriorRouterPort p1 = rtr.addVmPort().setVMAddress(ip1).build();
        rtr.addVmPort().setVMAddress(ip2).build();
        rtr.addVmPort().setVMAddress(ip3).build();

        p1.delete();
        t.delete();*/
    }

    @Test
    public void test3() {
        /*Tenant tenant1 = new Tenant.Builder(mgmt).setName("tenant-config-3")
            .build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();

        IPv4Addr tapAddr1 = IPv4Addr.fromString("192.168.66.2");
        ExteriorRouterPort p1 = router1.addVmPort().setVMAddress(tapAddr1).build();

        IPv4Addr tapAddr2 = IPv4Addr.fromString("192.168.66.3");
        ExteriorRouterPort p2 = router1.addVmPort().setVMAddress(tapAddr2).build();

        // The internal port has private address 192.168.55.5; floating ip
        // 10.0.173.5 is mapped to 192.168.55.5. Treat tapPort1 as the uplink:
        // only packets that go via the uplink use the the floatingIP.
        IPv4Addr privAddr = IPv4Addr.fromString("192.168.55.5");
        IPv4Addr pubAddr = IPv4Addr.fromString("10.0.173.5");
        ExteriorRouterPort p3 = router1.addVmPort().setVMAddress(privAddr).build();
        router1.addFilters();
        router1.addFloatingIp(privAddr, pubAddr, p1.port.getId());

        tenant1.delete();*/
    }

    @Test
    public void test4() {

        /*Tenant tenant1 = new Tenant.Builder(mgmt).setName("tenant-config-4")
            .build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();
        Router router2 = tenant1.addRouter().setName("rtr2").build();

        // Link the two routers.
        InteriorRouterPort router1port1 = router1.addLinkPort()
                .setNetworkAddress("169.254.1.0").setNetworkLength(30)
                .setPortAddress("169.254.1.1").build();
        InteriorRouterPort router2port1 = router2.addLinkPort()
                .setNetworkAddress("169.254.1.0").setNetworkLength(30)
                .setPortAddress("169.254.1.2").build();

        router1port1.link(router2port1, "192.168.231.0", "192.168.232.0");
        router1port1.unlink();

        tenant1.delete();*/
    }
}
