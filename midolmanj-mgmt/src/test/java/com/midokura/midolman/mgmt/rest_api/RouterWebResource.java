/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_ROUTER_JSON;
import static javax.ws.rs.core.Response.Status.CREATED;
import static javax.ws.rs.core.Response.Status.OK;
import static org.junit.Assert.assertEquals;

import java.net.URI;

import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

public class RouterWebResource {

    private final WebResource resource;

    public RouterWebResource(WebResource resource) {
        this.resource = resource;
    }

    public URI createRouter(URI uri, DtoRouter router) {
        return createRouter(uri, router, CREATED.getStatusCode());
    }

    public URI createRouter(URI uri, DtoRouter router, int status) {
        ClientResponse response = resource.uri(uri)
                .type(APPLICATION_ROUTER_JSON)
                .post(ClientResponse.class, router);
        assertEquals(status, response.getStatus());
        return response.getLocation();
    }

    public DtoRouter getRouter(URI uri) {
        return getRouter(uri, OK.getStatusCode());
    }

    public DtoRouter getRouter(URI uri, int status) {
        ClientResponse response = resource.uri(uri)
                .type(APPLICATION_ROUTER_JSON).get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        if (status == OK.getStatusCode()) {
            return response.getEntity(DtoRouter.class);
        } else {
            return null;
        }
    }
}
