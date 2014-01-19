/*
 * Copyright 2013 Midokura Europe SARL
 */

package org.midonet.api.dhcp;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoDhcpV6Host;
import org.midonet.client.dto.DtoDhcpSubnet6;
import static org.midonet.client.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_DHCPV6_HOST_COLLECTION_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_DHCPV6_HOST_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_DHCPV6_SUBNET_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_JSON_V2;

public class TestDHCPv6 extends JerseyTest {

    private DtoBridge bridge;

    public TestDHCPv6() {
        super(FuncTest.appDesc);
    }

    @Before
    public void before() {
        ClientResponse response;

        DtoApplication app = resource().path("").accept(APPLICATION_JSON_V2)
                .get(DtoApplication.class);

        bridge = new DtoBridge();
        bridge.setName("br1234");
        bridge.setTenantId("DhcpTenant");
        response = resource().uri(app.getBridges())
                .type(APPLICATION_BRIDGE_JSON)
                .post(ClientResponse.class, bridge);
        assertEquals("The bridge was created.", 201, response.getStatus());
        bridge = resource().uri(response.getLocation())
                .accept(APPLICATION_BRIDGE_JSON).get(DtoBridge.class);
    }

    @After
    public void resetDirectory() throws Exception {
        StaticMockDirectory.clearDirectoryInstance();
    }

    @Test
    public void testBadRequests() {
        // Test some bad network lengths
        DtoDhcpSubnet6 subnet1 = new DtoDhcpSubnet6();
        subnet1.setPrefix("dead:beef:feed::");
        subnet1.setPrefixLength(-10);
        ClientResponse response = resource().uri(bridge.getDhcpSubnet6s())
            .type(APPLICATION_DHCPV6_SUBNET_JSON)
            .post(ClientResponse.class, subnet1);

        assertEquals(400, response.getStatus());
        subnet1.setPrefixLength(129);
        response = resource().uri(bridge.getDhcpSubnet6s())
            .type(APPLICATION_DHCPV6_SUBNET_JSON)
            .post(ClientResponse.class, subnet1);
        assertEquals(400, response.getStatus());

        // Test some bad network addresses
        subnet1.setPrefixLength(64);
        subnet1.setPrefix("abcd::1234::");
        response = resource().uri(bridge.getDhcpSubnet6s())
            .type(APPLICATION_DHCPV6_SUBNET_JSON)
            .post(ClientResponse.class, subnet1);
        assertEquals(400, response.getStatus());
        subnet1.setPrefix("cat:dog::");
        response = resource().uri(bridge.getDhcpSubnet6s())
            .type(APPLICATION_DHCPV6_SUBNET_JSON)
            .post(ClientResponse.class, subnet1);
        assertEquals(400, response.getStatus());

    }

    @Test
    public void testSubnetCreateGetListDelete() {
        ClientResponse response;

        // Create a subnet
        DtoDhcpSubnet6 subnet1 = new DtoDhcpSubnet6();
        subnet1.setPrefix("abcd:1234:dead:a1a1:a:a:a:a");
        subnet1.setPrefixLength(63);
        response = resource().uri(bridge.getDhcpSubnet6s())
                .type(APPLICATION_DHCPV6_SUBNET_JSON)
                .post(ClientResponse.class, subnet1);
        assertEquals(201, response.getStatus());
        // Test GET
        subnet1 = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCPV6_SUBNET_JSON)
                .get(DtoDhcpSubnet6.class);
        assertEquals("abcd:1234:dead:a1a1:a:a:a:a", subnet1.getPrefix());
        assertEquals(63, subnet1.getPrefixLength());

        // Create another subnet
        DtoDhcpSubnet6 subnet2 = new DtoDhcpSubnet6();
        subnet2.setPrefix("1234:1234:1234:1234:1234:1234:1234:1234");
        subnet2.setPrefixLength(64);
        response = resource().uri(bridge.getDhcpSubnet6s())
                .type(APPLICATION_DHCPV6_SUBNET_JSON)
                .post(ClientResponse.class, subnet2);
        assertEquals(201, response.getStatus());
        subnet2 = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCPV6_SUBNET_JSON).get(DtoDhcpSubnet6.class);
        assertEquals("1234:1234:1234:1234:1234:1234:1234:1234", subnet2.getPrefix());
        assertEquals(64, subnet2.getPrefixLength());

        // List the subnets
        response = resource().uri(bridge.getDhcpSubnet6s())
                .accept(APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        DtoDhcpSubnet6[] subnets = response.getEntity(DtoDhcpSubnet6[].class);
        assertThat("We expect 2 listed subnets.", subnets, arrayWithSize(2));
        assertThat("We expect the listed subnets to match those we created.",
                subnets, arrayContainingInAnyOrder(
                subnet2, subnet1));

        // Delete the first subnet
        response = resource().uri(subnet1.getUri())
                .delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
        // There should now be only the second subnet.
        response = resource().uri(bridge.getDhcpSubnet6s())
                .accept(APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        subnets = response.getEntity(DtoDhcpSubnet6[].class);
        assertThat("We expect 1 listed subnet after the delete",
                subnets, arrayWithSize(1));
        assertThat("The listed subnet should be the one that wasn't deleted.",
                subnets, arrayContainingInAnyOrder(subnet2));

        // Test GET of a non-existing subnet (the deleted first subnet).
        response = resource().uri(subnet1.getUri())
                .accept(APPLICATION_DHCPV6_SUBNET_JSON).get(ClientResponse.class);
        assertEquals(404, response.getStatus());
    }

    @Test
    public void testSubnetCascadingDelete() {
        // Create a subnet with several configuration pieces: option 3,
        // option 121, and a host assignment. Then delete it.
        ClientResponse response;

        DtoDhcpSubnet6 subnet = new DtoDhcpSubnet6();
        subnet.setPrefix("dead:dead:dead:dead:0:0:0:0");
        subnet.setPrefixLength(64);
        response = resource().uri(bridge.getDhcpSubnet6s())
                .type(APPLICATION_DHCPV6_SUBNET_JSON)
                .post(ClientResponse.class, subnet);
        assertEquals(201, response.getStatus());
        subnet = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCPV6_SUBNET_JSON)
                .get(DtoDhcpSubnet6.class);

        DtoDhcpV6Host host1 = new DtoDhcpV6Host();
        host1.setFixedAddress("dead:dead:dead:dead:0:0:0:5");
        host1.setClientId("a:b:c");
        host1.setName("saturn");
        response =resource().uri(subnet.getHosts())
                .type(APPLICATION_DHCPV6_HOST_JSON)
                .post(ClientResponse.class, host1);
        assertEquals(201, response.getStatus());

        // List the subnets
        response = resource().uri(bridge.getDhcpSubnet6s())
                .accept(APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        DtoDhcpSubnet6[] subnets = response.getEntity(DtoDhcpSubnet6[].class);
        assertThat("We expect 1 listed subnets.", subnets, arrayWithSize(1));
        assertThat("We expect the listed subnets to match the one we created.",
                subnets, arrayContainingInAnyOrder(subnet));

        // Now delete the subnet.
        response = resource().uri(subnet.getUri()).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
        // Show that the list of DHCP subnet configurations is empty.
        response = resource().uri(bridge.getDhcpSubnet6s())
                .accept(APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        subnets = response.getEntity(DtoDhcpSubnet6[].class);
        assertThat("We expect 0 listed subnets.", subnets, arrayWithSize(0));
    }

    @Test
    public void testHosts() {
        // In this test remember that there will be multiple host definitions.
        // The system enforces that there is only one host def per mac address.
        ClientResponse response;

        DtoDhcpSubnet6 subnet = new DtoDhcpSubnet6();
        subnet.setPrefix("abcd:abcd:abcd:dead:0:0:0:0");
        subnet.setPrefixLength(64);
        response = resource().uri(bridge.getDhcpSubnet6s())
                .type(APPLICATION_DHCPV6_SUBNET_JSON)
                .post(ClientResponse.class, subnet);
        assertEquals(201, response.getStatus());
        subnet = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCPV6_SUBNET_JSON)
                .get(DtoDhcpSubnet6.class);

        DtoDhcpV6Host host1 = new DtoDhcpV6Host();
        host1.setFixedAddress("abcd:abcd:abcd:dead:0:0:0:a");
        host1.setClientId("a:b:c");
        host1.setName("saturn");
        response =resource().uri(subnet.getHosts())
                .type(APPLICATION_DHCPV6_HOST_JSON)
                .post(ClientResponse.class, host1);
        assertEquals(201, response.getStatus());
        host1 = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCPV6_HOST_JSON).get(DtoDhcpV6Host.class);
        assertEquals("abcd:abcd:abcd:dead:0:0:0:a", host1.getFixedAddress());
        assertEquals("saturn", host1.getName());

        DtoDhcpV6Host host2 = new DtoDhcpV6Host();
        host2.setFixedAddress("abcd:abcd:abcd:dead:0:0:0:b");
        host2.setClientId("b:c:d");
        host2.setName("jupiter");
        response =resource().uri(subnet.getHosts())
                .type(APPLICATION_DHCPV6_HOST_JSON).post(ClientResponse.class, host2);
        assertEquals(201, response.getStatus());
        host2 = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCPV6_HOST_JSON).get(DtoDhcpV6Host.class);
        assertEquals("abcd:abcd:abcd:dead:0:0:0:b", host2.getFixedAddress());
        assertEquals("jupiter", host2.getName());

        // Now list all the host static assignments.
        response = resource().uri(subnet.getHosts())
                .accept(APPLICATION_DHCPV6_HOST_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());

        DtoDhcpV6Host[] hosts = response.getEntity(DtoDhcpV6Host[].class);
        assertThat("We expect 2 listed hosts.", hosts, arrayWithSize(2));
        assertThat("We expect the listed hosts to match those we created.",
                hosts, arrayContainingInAnyOrder(host2, host1));

        // Now try to create a new host with host1's mac address. This should
        // fail.
        host1.setFixedAddress("abcd:abcd:abcd:dead:0:0:0:c");
        response =resource().uri(subnet.getHosts())
                .type(APPLICATION_DHCPV6_HOST_JSON)
                .post(ClientResponse.class, host1);
        assertEquals(500, response.getStatus());

        // Try again, this time using an UPDATE operation.
        response =resource().uri(host1.getUri())
                .type(APPLICATION_DHCPV6_HOST_JSON)
                .put(ClientResponse.class, host1);
        assertEquals(200, response.getStatus());
        host1 = resource().uri(host1.getUri())
                .accept(APPLICATION_DHCPV6_HOST_JSON)
                .get(DtoDhcpV6Host.class);
        assertEquals("abcd:abcd:abcd:dead:0:0:0:c", host1.getFixedAddress());
        assertEquals("saturn", host1.getName());

        // There should still be exactly 2 host assignments.
        response = resource().uri(subnet.getHosts())
                .accept(APPLICATION_DHCPV6_HOST_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        hosts = response.getEntity(DtoDhcpV6Host[].class);
        assertThat("We expect 2 listed hosts.", hosts, arrayWithSize(2));
        assertThat("We expect the listed hosts to match those we created.",
                hosts, arrayContainingInAnyOrder(host1, host2));

        // Now delete one of the host assignments.
        response = resource().uri(host1.getUri()).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
        // There should now be only 1 host assignment.
        response = resource().uri(subnet.getHosts())
                .accept(APPLICATION_DHCPV6_HOST_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        hosts = response.getEntity(DtoDhcpV6Host[].class);
        assertThat("We expect 1 listed host after the delete",
                hosts, arrayWithSize(1));
        assertThat("The listed hosts should be the one that wasn't deleted.",
                hosts, arrayContainingInAnyOrder(host2));
    }
}
