/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.rest_api;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.client.DtoApplication;
import com.midokura.midolman.mgmt.data.dto.client.DtoPortGroup;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;


import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.*;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class TestPortGroup extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestPortGroup.class);
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
        assertEquals("The bridge was created.", 201, response.getStatus());
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
