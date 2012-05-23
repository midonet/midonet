/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_CHAIN_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORTGROUP_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORTGROUP_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORT_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_RULE_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_JSON;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.util.UUID;

import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dto.client.DtoApplication;
import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoPortGroup;
import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.test.framework.JerseyTest;

public class TestPortGroup extends JerseyTest {

    DtoTenant tenant1;
    DtoTenant tenant2;

    public TestPortGroup() {
        super(FuncTest.appDesc);
    }

    @Before
    public void before() {
        ClientResponse response;

        DtoApplication app = new DtoApplication();
        app = resource().path("")
                .type(APPLICATION_JSON)
                .get(DtoApplication.class);

        tenant1 = new DtoTenant();
        tenant1.setId("GroupTenant1");
        response = resource().uri(app.getTenant())
                .type(APPLICATION_TENANT_JSON)
                .post(ClientResponse.class, tenant1);
        assertEquals("The tenant was created.", 201, response.getStatus());
        tenant1 = resource().uri(response.getLocation())
                .accept(APPLICATION_TENANT_JSON)
                .get(DtoTenant.class);

        tenant2 = new DtoTenant();
        tenant2.setId("GroupTenant2");
        response = resource().uri(app.getTenant())
                .type(APPLICATION_TENANT_JSON)
                .post(ClientResponse.class, tenant2);
        assertEquals("The tenant was created.", 201, response.getStatus());
        tenant2 = resource().uri(response.getLocation())
                .accept(APPLICATION_TENANT_JSON)
                .get(DtoTenant.class);
    }

    @Test
    public void testCreateGetListDelete() {
        ClientResponse response;

        // Create a port group for Tenant1
        DtoPortGroup group1 = new DtoPortGroup();
        group1.setName("Group1");
        response = resource().uri(tenant1.getPortGroups())
                .type(APPLICATION_PORTGROUP_JSON)
                .post(ClientResponse.class, group1);
        assertEquals("The PortGroup was created.", 201, response.getStatus());
        group1 = resource().uri(response.getLocation())
                .accept(APPLICATION_PORTGROUP_JSON)
                .get(DtoPortGroup.class);
        assertEquals("Group1", group1.getName());
        assertEquals(tenant1.getId(), group1.getTenantId());

        // Create another port group for Tenant1
        DtoPortGroup group2 = new DtoPortGroup();
        group2.setName("Group2");
        response = resource().uri(tenant1.getPortGroups())
                .type(APPLICATION_PORTGROUP_JSON)
                .post(ClientResponse.class, group2);
        assertEquals("The bridge was created.", 201, response.getStatus());
        group2 = resource().uri(response.getLocation())
                .accept(APPLICATION_PORTGROUP_JSON)
                .get(DtoPortGroup.class);
        assertEquals("Group2", group2.getName());
        assertEquals(tenant1.getId(), group2.getTenantId());

        // Create a port group for Tenant2
        DtoPortGroup group3 = new DtoPortGroup();
        group3.setName("Group3");
        response = resource().uri(tenant2.getPortGroups())
                .type(APPLICATION_PORTGROUP_JSON)
                .post(ClientResponse.class, group3);
        assertEquals("The bridge was created.", 201, response.getStatus());
        group3 = resource().uri(response.getLocation())
                .accept(APPLICATION_PORTGROUP_JSON)
                .get(DtoPortGroup.class);
        assertEquals("Group3", group3.getName());
        assertEquals(tenant2.getId(), group3.getTenantId());

        // List tenant1's groups
        response = resource().uri(tenant1.getPortGroups())
                .accept(APPLICATION_PORTGROUP_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        DtoPortGroup[] groups = response.getEntity(DtoPortGroup[].class);
        assertThat("Tenant1 has 2 groups.", groups, arrayWithSize(2));
        assertThat("We expect the listed groups to match those we created.",
                groups, arrayContainingInAnyOrder(group1, group2));

        // Create a bridge for Tenant1
        DtoBridge bridge = new DtoBridge();
        bridge.setName("Bridge1");
        response = resource().uri(tenant1.getBridges())
                .type(APPLICATION_BRIDGE_JSON)
                .post(ClientResponse.class, bridge);
        assertEquals("The bridge was created.", 201, response.getStatus());
        bridge = resource().uri(response.getLocation())
                .accept(APPLICATION_BRIDGE_JSON)
                .get(DtoBridge.class);
        assertEquals("Bridge1", bridge.getName());
        assertEquals(tenant1.getId(), bridge.getTenantId());
        // Create a port on Bridge1 that belongs to both of Tenant1's PortGroups
        DtoPort port = new DtoPort();
        port.setPortGroupIDs(new UUID[] {group1.getId(), group2.getId()});
        response = resource().uri(bridge.getPorts())
                .type(APPLICATION_PORT_JSON)
                .post(ClientResponse.class, port);
        assertEquals("The port was created.", 201, response.getStatus());
        port = resource().uri(response.getLocation())
                .accept(APPLICATION_PORT_JSON)
                .get(DtoPort.class);
        assertEquals("Bridge1", bridge.getName());
        assertEquals(bridge.getId(), port.getDeviceId());
        assertThat("The port's groups should be group1 and group2",
                port.getPortGroupIDs(),
                arrayContainingInAnyOrder(group1.getId(), group2.getId()));

        // Now create a rule chain with a rule that matches group1 and group2.
        DtoRuleChain chain = new DtoRuleChain();
        chain.setName("Chain1");
        response = resource().uri(tenant1.getChains())
                .type(APPLICATION_CHAIN_JSON)
                .post(ClientResponse.class, chain);
        assertEquals("The chain was created.", 201, response.getStatus());
        chain = resource().uri(response.getLocation())
                .accept(APPLICATION_CHAIN_JSON)
                .get(DtoRuleChain.class);
        assertEquals("Chain1", chain.getName());
        assertEquals(tenant1.getId(), chain.getTenantId());
        // Now add the rule.
        DtoRule rule = new DtoRule();
        rule.setPosition(1);
        rule.setType(DtoRule.Accept);
        rule.setNwSrcAddress("10.11.12.13");
        rule.setNwSrcLength(32);
        UUID[] fakePortIDs = new UUID[] {UUID.randomUUID(), UUID.randomUUID()};
        rule.setInPorts(fakePortIDs);
        rule.setPortGroup(group1.getId());
        response = resource().uri(chain.getRules())
                .type(APPLICATION_RULE_JSON)
                .post(ClientResponse.class, rule);
        assertEquals("The rule was created.", 201, response.getStatus());
        rule = resource().uri(response.getLocation())
                .accept(APPLICATION_RULE_JSON)
                .get(DtoRule.class);
        assertEquals(chain.getId(), rule.getChainId());
        assertEquals(DtoRule.Accept, rule.getType());
        assertEquals("10.11.12.13", rule.getNwSrcAddress());
        assertEquals(32, rule.getNwSrcLength());
        assertThat("The rule should match the fake ingress ports",
                rule.getInPorts(),
                arrayContainingInAnyOrder(fakePortIDs[0], fakePortIDs[1]));
        assertThat("The rule should match group1",
                rule.getPortGroup(), equalTo(group1.getId()));


        // Delete the first group
        response = resource().uri(group1.getUri())
                .delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
        // There should now be only the second group.
        response = resource().uri(tenant1.getPortGroups())
                .accept(APPLICATION_PORTGROUP_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        groups = response.getEntity(DtoPortGroup[].class);
        assertThat("We expect 1 listed group after the delete",
                groups, arrayWithSize(1));
        assertThat("The listed group should be the one that wasn't deleted.",
                groups, arrayContainingInAnyOrder(group2));

        // Test GET of a non-existing group (the deleted first group).
        response = resource().uri(group1.getUri())
                .accept(APPLICATION_PORTGROUP_JSON).get(ClientResponse.class);
        assertEquals(404, response.getStatus());

        // TODO(pino): all these cases should fail:
        // TODO:  1) Set a Port's group to a GroupID owned by another Tenant.
    }

}