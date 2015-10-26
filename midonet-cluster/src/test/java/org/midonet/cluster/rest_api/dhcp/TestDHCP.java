/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.rest_api.dhcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.test.framework.JerseyTest;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoDhcpHost;
import org.midonet.client.dto.DtoDhcpOption121;
import org.midonet.client.dto.DtoDhcpSubnet;
import org.midonet.client.dto.DtoExtraDhcpOpt;
import org.midonet.cluster.rest_api.models.ErrorEntity;
import org.midonet.cluster.rest_api.rest_api.FuncTest;
import org.midonet.cluster.rest_api.validation.MessageProperty;

import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BRIDGE_JSON_V4;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_DHCP_HOST_COLLECTION_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_DHCP_HOST_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_DHCP_SUBNET_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_JSON_V5;

public class TestDHCP extends JerseyTest {

    private DtoBridge bridge;

    public TestDHCP() {
        super(FuncTest.appDesc);
    }

    @Before
    public void before() {
        ClientResponse response;

        DtoApplication app = resource().path("").accept(APPLICATION_JSON_V5())
                .get(DtoApplication.class);

        bridge = new DtoBridge();
        bridge.setName("br1234");
        bridge.setTenantId("DhcpTenant");
        response = resource().uri(app.getBridges())
                .type(APPLICATION_BRIDGE_JSON_V4())
                .post(ClientResponse.class, bridge);
        assertEquals("The bridge was created.", 201, response.getStatus());
        bridge = resource().uri(response.getLocation())
                .accept(APPLICATION_BRIDGE_JSON_V4()).get(DtoBridge.class);
    }

    @Test
    public void testEnabled() {
        ClientResponse response;

        // Create an enabled subnet
        DtoDhcpSubnet subnet1 = new DtoDhcpSubnet();
        subnet1.setSubnetPrefix("10.0.0.0");
        subnet1.setSubnetLength(24);
        response = resource().uri(bridge.getDhcpSubnets())
                .type(APPLICATION_DHCP_SUBNET_JSON_V2())
                .post(ClientResponse.class, subnet1);
        assertEquals(201, response.getStatus());

        // Test GET
        subnet1 = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCP_SUBNET_JSON_V2())
                .get(DtoDhcpSubnet.class);
        assertEquals(true, subnet1.isEnabled());

        // Disable a subnet via update
        subnet1.setEnabled(false);
        response = resource().uri(subnet1.getUri())
                .type(APPLICATION_DHCP_SUBNET_JSON_V2())
                .put(ClientResponse.class, subnet1);
        assertEquals(204, response.getStatus());

        subnet1 = resource().uri(subnet1.getUri())
                .accept(APPLICATION_DHCP_SUBNET_JSON_V2())
                .get(DtoDhcpSubnet.class);
        assertEquals(false, subnet1.isEnabled());
    }

    @Test
    public void testBadRequests() {
        // Test some bad network lengths
        DtoDhcpSubnet subnet1 = new DtoDhcpSubnet();
        subnet1.setSubnetPrefix("172.31.0.0");
        subnet1.setSubnetLength(33);
        subnet1.setDefaultGateway("172.31.0.1");
        subnet1.setServerAddr("172.31.0.118");
        ClientResponse response = resource().uri(bridge.getDhcpSubnets())
            .type(APPLICATION_DHCP_SUBNET_JSON_V2())
            .post(ClientResponse.class, subnet1);
        assertEquals(400, response.getStatus());
        subnet1.setSubnetLength(33);
        response = resource().uri(bridge.getDhcpSubnets())
            .type(APPLICATION_DHCP_SUBNET_JSON_V2())
            .post(ClientResponse.class, subnet1);
        assertEquals(400, response.getStatus());

        // Test some bad network addresses
        subnet1.setSubnetLength(24);
        subnet1.setSubnetPrefix("10.0.0");
        response = resource().uri(bridge.getDhcpSubnets())
            .type(APPLICATION_DHCP_SUBNET_JSON_V2())
            .post(ClientResponse.class, subnet1);
        assertEquals(400, response.getStatus());
        subnet1.setSubnetPrefix("321.4.5.6");
        response = resource().uri(bridge.getDhcpSubnets())
            .type(APPLICATION_DHCP_SUBNET_JSON_V2())
            .post(ClientResponse.class, subnet1);
        assertEquals(400, response.getStatus());

        // Test some bad default gateways.
        subnet1.setSubnetPrefix("172.31.0.0");
        subnet1.setDefaultGateway("nonsense");
        response = resource().uri(bridge.getDhcpSubnets())
            .type(APPLICATION_DHCP_SUBNET_JSON_V2())
            .post(ClientResponse.class, subnet1);
        assertEquals(400, response.getStatus());
        subnet1.setDefaultGateway("1.2.3.4.5");
        response = resource().uri(bridge.getDhcpSubnets())
            .type(APPLICATION_DHCP_SUBNET_JSON_V2())
            .post(ClientResponse.class, subnet1);
        assertEquals(400, response.getStatus());
    }

    @Test
    public void testGatewaySetting() throws Exception {
        ClientResponse response;

        DtoDhcpSubnet subnet1 = new DtoDhcpSubnet();
        subnet1.setSubnetPrefix("172.31.0.0");
        subnet1.setSubnetLength(24);
        subnet1.setDefaultGateway("172.31.0.1");
        subnet1.setServerAddr("172.31.0.118");
        response = resource().uri(bridge.getDhcpSubnets())
            .type(APPLICATION_DHCP_SUBNET_JSON_V2())
            .post(ClientResponse.class, subnet1);

        assertEquals(201, response.getStatus());

        DtoDhcpSubnet[] subnets =
            resource().uri(bridge.getDhcpSubnets())
                .type(APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2())
                .get(DtoDhcpSubnet[].class);
        Assert.assertNotNull(subnets);
    }

    @Test
    public void testSubnetCreateUpdateGetListDelete() {
        ClientResponse response;

        // Create a subnet
        DtoDhcpSubnet subnet1 = new DtoDhcpSubnet();
        subnet1.setSubnetPrefix("172.31.0.0");
        subnet1.setSubnetLength(24);
        subnet1.setServerAddr("172.31.0.118");
        response = resource().uri(bridge.getDhcpSubnets())
                .type(APPLICATION_DHCP_SUBNET_JSON_V2())
                .post(ClientResponse.class, subnet1);
        assertEquals(201, response.getStatus());
        // Test GET
        subnet1 = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCP_SUBNET_JSON_V2())
                .get(DtoDhcpSubnet.class);
        assertEquals("172.31.0.0", subnet1.getSubnetPrefix());
        assertEquals(24, subnet1.getSubnetLength());

        // Create another subnet
        DtoDhcpSubnet subnet2 = new DtoDhcpSubnet();
        subnet2.setSubnetPrefix("172.31.1.0");
        subnet2.setSubnetLength(24);
        subnet2.setServerAddr("172.31.1.118");
        response = resource().uri(bridge.getDhcpSubnets())
                .type(APPLICATION_DHCP_SUBNET_JSON_V2())
                .post(ClientResponse.class, subnet2);
        assertEquals(201, response.getStatus());
        subnet2 = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCP_SUBNET_JSON_V2()).get(DtoDhcpSubnet.class);
        assertEquals("172.31.1.0", subnet2.getSubnetPrefix());
        assertEquals(24, subnet2.getSubnetLength());

        // List the subnets
        response = resource().uri(bridge.getDhcpSubnets())
                .accept(APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2())
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        DtoDhcpSubnet[] subnets = response.getEntity(DtoDhcpSubnet[].class);
        assertThat("We expect 2 listed subnets.", subnets, arrayWithSize(2));
        assertThat("We expect the listed subnets to match those we created.",
                subnets, arrayContainingInAnyOrder(
                subnet1, subnet2));

        // Delete the first subnet
        response = resource().uri(subnet1.getUri())
                .delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
        // There should now be only the second subnet.
        response = resource().uri(bridge.getDhcpSubnets())
                .accept(APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2())
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        subnets = response.getEntity(DtoDhcpSubnet[].class);
        assertThat("We expect 1 listed subnet after the delete",
                subnets, arrayWithSize(1));
        assertThat("The listed subnet should be the one that wasn't deleted.",
                subnets, arrayContainingInAnyOrder(subnet2));

        // Test GET of a non-existing subnet (the deleted first subnet).
        response = resource().uri(subnet1.getUri())
                .accept(APPLICATION_DHCP_SUBNET_JSON_V2()).get(ClientResponse.class);
        assertEquals(404, response.getStatus());

        // Delete a non-existing subnet.
        response = resource().uri(subnet1.getUri())
            .delete(ClientResponse.class);
        assertEquals(404, response.getStatus());
        String expectedMsg = MessageProperty
            .getMessage(MessageProperty.NETWORK_SUBNET_NOT_FOUND,
                        bridge.getId(),
                        subnet1.getSubnetPrefix() + "_" +
                        subnet1.getSubnetLength());
        ErrorEntity error = response.getEntity(ErrorEntity.class);
        assertEquals(expectedMsg, error.getMessage());

        // Update a non-existing subnet.
        response = resource().uri(subnet1.getUri())
            .type(APPLICATION_DHCP_SUBNET_JSON_V2())
            .put(ClientResponse.class, subnet1);
        assertEquals(404, response.getStatus());
        error = response.getEntity(ErrorEntity.class);
        assertEquals(expectedMsg, error.getMessage());
    }

    @Test
    public void testSubnetCascadingDelete() {
        // Create a subnet with several configuration pieces: option 3,
        // option 121, and a host assignment. Then delete it.
        ClientResponse response;

        DtoDhcpSubnet subnet = new DtoDhcpSubnet();
        subnet.setSubnetPrefix("172.31.0.0");
        subnet.setSubnetLength(24);
        subnet.setServerAddr("172.31.0.118");
        response = resource().uri(bridge.getDhcpSubnets())
                .type(APPLICATION_DHCP_SUBNET_JSON_V2())
                .post(ClientResponse.class, subnet);
        assertEquals(201, response.getStatus());
        subnet = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCP_SUBNET_JSON_V2())
                .get(DtoDhcpSubnet.class);

        DtoDhcpHost host1 = new DtoDhcpHost();
        host1.setMacAddr("02:33:44:55:00:00");
        host1.setIpAddr("172.31.0.11");
        host1.setName("saturn");
        host1.setExtraDhcpOpts(Collections.<DtoExtraDhcpOpt>emptyList());
        response =resource().uri(subnet.getHosts())
                .type(APPLICATION_DHCP_HOST_JSON_V2())
                .post(ClientResponse.class, host1);
        assertEquals(201, response.getStatus());

        // List the subnets
        response = resource().uri(bridge.getDhcpSubnets())
                .accept(APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2())
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        DtoDhcpSubnet[] subnets = response.getEntity(DtoDhcpSubnet[].class);
        assertThat("We expect 1 listed subnets.", subnets, arrayWithSize(1));
        assertThat("We expect the listed subnets to match the one we created.",
                subnets, arrayContainingInAnyOrder(subnet));

        // Now delete the subnet.
        response = resource().uri(subnet.getUri()).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
        // Show that the list of DHCP subnet configurations is empty.
        response = resource().uri(bridge.getDhcpSubnets())
                .accept(APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2())
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        subnets = response.getEntity(DtoDhcpSubnet[].class);
        assertThat("We expect 0 listed subnets.", subnets, arrayWithSize(0));
    }

    @Test
    public void testDhcpRoutes() {
        ClientResponse response;

        DtoDhcpSubnet subnet1 = new DtoDhcpSubnet();
        subnet1.setSubnetPrefix("172.0.0.0");
        subnet1.setSubnetLength(24);
        subnet1.setDefaultGateway("172.0.0.254");
        subnet1.setServerAddr("172.0.0.118");
        subnet1.getOpt121Routes().add(
            new DtoDhcpOption121("172.31.1.0", 24, "172.0.0.253"));
        subnet1.getOpt121Routes().add(
            new DtoDhcpOption121("172.31.2.0", 24, "172.0.0.253"));
        response = resource().uri(bridge.getDhcpSubnets())
                .type(APPLICATION_DHCP_SUBNET_JSON_V2())
                .post(ClientResponse.class, subnet1);
        assertEquals(201, response.getStatus());
        DtoDhcpSubnet subnet2 = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCP_SUBNET_JSON_V2())
                .get(DtoDhcpSubnet.class);

        // Copy the URIs from the GET to the DTO we used for create.
        subnet1.setHosts(subnet2.getHosts());
        subnet1.setUri(subnet2.getUri());
        // Now the DTOs should be identical.


        assertEquals("The DhcpSubnet client dto from GET should be " +
                     "identical to the one passed to create", subnet1, subnet2);

        // Now modify the routes and do an update.
        subnet1.getOpt121Routes().remove(0);
        subnet1.getOpt121Routes().add(
                new DtoDhcpOption121("172.31.3.0", 24, "172.0.0.252"));
        subnet1.getOpt121Routes().add(
            new DtoDhcpOption121("172.31.4.0", 24, "172.0.0.253"));
        subnet1.setDefaultGateway("172.0.0.1");
        response = resource().uri(subnet1.getUri())
                .type(APPLICATION_DHCP_SUBNET_JSON_V2())
                .put(ClientResponse.class, subnet1);
        assertEquals(204, response.getStatus());
        subnet2 = resource().uri(subnet1.getUri())
                .accept(APPLICATION_DHCP_SUBNET_JSON_V2())
                .get(DtoDhcpSubnet.class);
        // The URIs should not have changed, so the DTOs should be identical.
        assertEquals("The DhcpSubnet client dto from GET should be " +
                "identical to the one passed to create", subnet1, subnet2);

        // Now try removing all routes.
        subnet1.getOpt121Routes().clear();
        subnet1.setDefaultGateway(null);
        response = resource().uri(subnet1.getUri())
                .type(APPLICATION_DHCP_SUBNET_JSON_V2())
                .put(ClientResponse.class, subnet1);
        assertEquals(204, response.getStatus());
        subnet2 = resource().uri(subnet1.getUri())
                .accept(APPLICATION_DHCP_SUBNET_JSON_V2())
                .get(DtoDhcpSubnet.class);
        // The URIs should not have changed, so the DTOs should be identical.
        assertEquals("The DhcpSubnet client dto from GET should be " +
                "identical to the one passed to create", subnet1, subnet2);
    }

    @Test
    public void testHosts() {
        // In this test remember that there will be multiple host definitions.
        // The system enforces that there is only one host def per mac address.
        ClientResponse response;

        DtoDhcpSubnet subnet = new DtoDhcpSubnet();
        subnet.setSubnetPrefix("172.31.0.0");
        subnet.setSubnetLength(24);
        subnet.setServerAddr("172.31.0.118");
        response = resource().uri(bridge.getDhcpSubnets())
                .type(APPLICATION_DHCP_SUBNET_JSON_V2())
                .post(ClientResponse.class, subnet);
        assertEquals(201, response.getStatus());
        subnet = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCP_SUBNET_JSON_V2())
                .get(DtoDhcpSubnet.class);

        DtoDhcpHost host1 = new DtoDhcpHost();
        List<DtoExtraDhcpOpt> opts = new ArrayList<>();
        opts.add(new DtoExtraDhcpOpt("name1", "val1"));
        opts.add(new DtoExtraDhcpOpt("name2", "val2"));
        opts.add(new DtoExtraDhcpOpt("name3", "val3"));
        host1.setMacAddr("02:33:44:55:00:00");
        host1.setIpAddr("172.31.0.11");
        host1.setName("saturn");
        host1.setExtraDhcpOpts(opts);
        response =resource().uri(subnet.getHosts())
                .type(APPLICATION_DHCP_HOST_JSON_V2())
                .post(ClientResponse.class, host1);
        assertEquals(201, response.getStatus());
        host1 = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCP_HOST_JSON_V2()).get(DtoDhcpHost.class);
        assertEquals("02:33:44:55:00:00", host1.getMacAddr());
        assertEquals("172.31.0.11", host1.getIpAddr());
        assertEquals("saturn", host1.getName());
        assertEquals(opts, host1.getExtraDhcpOpts());

        DtoDhcpHost host2 = new DtoDhcpHost();
        host2.setMacAddr("02:33:44:55:00:01");
        host2.setIpAddr("172.31.0.12");
        host2.setName("jupiter");
        List<DtoExtraDhcpOpt> opts2 = new ArrayList<>();
        opts2.add(new DtoExtraDhcpOpt("name1", "val1"));
        opts2.add(new DtoExtraDhcpOpt("name2", "val2"));
        opts2.add(new DtoExtraDhcpOpt("name3", "val3"));
        host2.setExtraDhcpOpts(opts2);
        response =resource().uri(subnet.getHosts())
                .type(APPLICATION_DHCP_HOST_JSON_V2()).post(ClientResponse.class, host2);
        assertEquals(201, response.getStatus());
        host2 = resource().uri(response.getLocation())
                .accept(APPLICATION_DHCP_HOST_JSON_V2()).get(DtoDhcpHost.class);
        assertEquals("02:33:44:55:00:01", host2.getMacAddr());
        assertEquals("172.31.0.12", host2.getIpAddr());
        assertEquals("jupiter", host2.getName());
        assertEquals(opts, host2.getExtraDhcpOpts());

        // Now list all the host static assignments.
        response = resource().uri(subnet.getHosts())
                .accept(APPLICATION_DHCP_HOST_COLLECTION_JSON_V2())
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());

        DtoDhcpHost[] hosts = response.getEntity(DtoDhcpHost[].class);
        assertThat("We expect 2 listed hosts.", hosts, arrayWithSize(2));
        assertThat("We expect the listed hosts to match those we created.",
                hosts, arrayContainingInAnyOrder(host2, host1));

        // Now try to create a new host with host1's mac address. This should
        // fail.
        host1.setIpAddr("172.31.0.13");
        response = resource().uri(subnet.getHosts())
                .type(APPLICATION_DHCP_HOST_JSON_V2())
                .post(ClientResponse.class, host1);
        assertEquals(409, response.getStatus());
        String expectedMsg = MessageProperty.getMessage(
            MessageProperty.SUBNET_HAS_HOST, "172.31.0.0/24", bridge.getId(),
            host1.getMacAddr());
        ErrorEntity error = response.getEntity(ErrorEntity.class);
        assertEquals(expectedMsg, error.getMessage());

        // Try again, this time using an UPDATE operation.
        response =resource().uri(host1.getUri())
                .type(APPLICATION_DHCP_HOST_JSON_V2())
                .put(ClientResponse.class, host1);
        assertEquals(204, response.getStatus());
        host1 = resource().uri(host1.getUri())
                .accept(APPLICATION_DHCP_HOST_JSON_V2())
                .get(DtoDhcpHost.class);
        assertEquals("02:33:44:55:00:00", host1.getMacAddr());
        assertEquals("172.31.0.13", host1.getIpAddr());
        assertEquals("saturn", host1.getName());

        // There should still be exactly 2 host assignments.
        response = resource().uri(subnet.getHosts())
                .accept(APPLICATION_DHCP_HOST_COLLECTION_JSON_V2())
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        hosts = response.getEntity(DtoDhcpHost[].class);
        assertThat("We expect 2 listed hosts.", hosts, arrayWithSize(2));
        assertThat("We expect the listed hosts to match those we created.",
                hosts, arrayContainingInAnyOrder(host1, host2));

        // Now delete one of the host assignments.
        response = resource().uri(host1.getUri()).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
        // There should now be only 1 host assignment.
        response = resource().uri(subnet.getHosts())
                .accept(APPLICATION_DHCP_HOST_COLLECTION_JSON_V2())
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        hosts = response.getEntity(DtoDhcpHost[].class);
        assertThat("We expect 1 listed host after the delete",
                hosts, arrayWithSize(1));
        assertThat("The listed hosts should be the one that wasn't deleted.",
                hosts, arrayContainingInAnyOrder(host2));

        // Delete a non-existing host.
        response = resource().uri(host1.getUri()).delete(ClientResponse.class);
        assertEquals(404, response.getStatus());
        expectedMsg = MessageProperty
            .getMessage(MessageProperty.NETWORK_SUBNET_NOT_FOUND,
                        bridge.getId(),
                        subnet.getSubnetPrefix() + "/" +
                        subnet.getSubnetLength());
        error = response.getEntity(ErrorEntity.class);
        assertEquals(expectedMsg, error.getMessage());

        // Update a non-existing host.
        response = resource().uri(host1.getUri())
            .type(APPLICATION_DHCP_HOST_JSON_V2())
            .put(ClientResponse.class, host1);
        assertEquals(404, response.getStatus());
        error = response.getEntity(ErrorEntity.class);
        assertEquals(expectedMsg, error.getMessage());
    }
}
