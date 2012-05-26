/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static javax.ws.rs.core.Response.Status.CREATED;
import static javax.ws.rs.core.Response.Status.OK;
import static org.junit.Assert.assertEquals;

import java.net.URI;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

public class BridgeWebResource {

    private final WebResource resource;

    public BridgeWebResource(WebResource resource) {
        this.resource = resource;
    }

    public URI createBridge(URI uri, DtoBridge bridge) {
        return createBridge(uri, bridge, CREATED.getStatusCode());
    }

    public URI createBridge(URI uri, DtoBridge bridge, int status) {
        ClientResponse response = resource.uri(uri)
                .type(APPLICATION_BRIDGE_JSON)
                .post(ClientResponse.class, bridge);
        assertEquals(status, response.getStatus());
        return response.getLocation();
    }

    public DtoBridge getBridge(URI uri) {
        return getBridge(uri, OK.getStatusCode());
    }

    public DtoBridge getBridge(URI uri, int status) {
        ClientResponse response = resource.uri(uri)
                .type(APPLICATION_BRIDGE_JSON).get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        if (status == OK.getStatusCode()) {
            return response.getEntity(DtoBridge.class);
        } else {
            return null;
        }
    }
}
