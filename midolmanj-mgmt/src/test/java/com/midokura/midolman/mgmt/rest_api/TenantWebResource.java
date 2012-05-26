/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_JSON;
import static javax.ws.rs.core.Response.Status.CREATED;
import static javax.ws.rs.core.Response.Status.OK;
import static org.junit.Assert.assertEquals;

import java.net.URI;

import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

public class TenantWebResource {

    private final WebResource resource;

    public TenantWebResource(WebResource resource) {
        this.resource = resource;
    }

    public URI createTenant(DtoTenant tenant) {
        return createTenant(tenant, CREATED.getStatusCode());
    }

    public URI createTenant(DtoTenant tenant, int status) {
        ClientResponse response = resource.path("tenants")
                .type(APPLICATION_TENANT_JSON)
                .post(ClientResponse.class, tenant);
        assertEquals(status, response.getStatus());
        return response.getLocation();
    }

    public DtoTenant getTenant(URI uri) {
        return getTenant(uri, OK.getStatusCode());
    }

    public DtoTenant getTenant(URI uri, int status) {
        ClientResponse response = resource.uri(uri)
                .type(APPLICATION_TENANT_JSON).get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        if (status == OK.getStatusCode()) {
            return response.getEntity(DtoTenant.class);
        } else {
            return null;
        }
    }
}
