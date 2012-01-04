/*
 * @(#)TestTenant        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_JSON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;



public class TestTenant extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestTenant.class);
    private final String testTenantName = "TEST-TENANT";

    private WebResource resource;
    private ClientResponse response;


    public TestTenant() {
        super(FuncTest.appDesc);
    }

    @Before
    public void before() {

        DtoTenant tenant = new DtoTenant();
        tenant.setId(testTenantName);

        resource = resource().path("tenants");
        response = resource.type(APPLICATION_TENANT_JSON).post(
                ClientResponse.class, tenant);
        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", response.getLocation());
        assertEquals(201, response.getStatus());
        assertTrue(response.getLocation().toString().endsWith("tenants/" + testTenantName));
    }

    @Test
    public void testCreateWithEmptyBody() {

        resource = resource().path("tenants");
        response = resource.type(APPLICATION_TENANT_JSON).post(
                ClientResponse.class, "{}");
        String body = response.getEntity(String.class);

        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", response.getLocation());
        log.debug("body: {}", body);

        String idString = response.getLocation().toString();
        idString = idString.substring(idString.lastIndexOf("/") + 1, idString.length());
        log.debug("idString: {}", idString);
        try {
            UUID.fromString(idString);
        } catch (Exception e) {
            Assert.fail("failed: returned tenant id doesn't conform to UUID form." +  e);
        }
    }

    @Test
    public void testList() {

        resource = resource().path("tenants");
        response = resource.type(APPLICATION_TENANT_JSON).get(
                ClientResponse.class);
        String body = response.getEntity(String.class);
        log.debug("status: {}", response.getStatus());
        log.debug("body: {}", body);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testDelete() {
        resource = resource().path("tenants/" + testTenantName);
        response = resource.type(APPLICATION_TENANT_JSON).delete(
                ClientResponse.class);
        log.debug("status: {}", response.getStatus());
        assertEquals(204, response.getStatus());
    }
}
