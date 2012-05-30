/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_CHAIN_JSON;
import static javax.ws.rs.core.Response.Status.CREATED;
import static javax.ws.rs.core.Response.Status.OK;
import static org.junit.Assert.assertEquals;

import java.net.URI;

import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

public class ChainWebResource {

    private final WebResource resource;

    public ChainWebResource(WebResource resource) {
        this.resource = resource;
    }

    public URI createChain(URI uri, DtoRuleChain chain) {
        return createChain(uri, chain, CREATED.getStatusCode());
    }

    public URI createChain(URI uri, DtoRuleChain chain, int status) {
        ClientResponse response = resource.uri(uri)
                .type(APPLICATION_CHAIN_JSON).post(ClientResponse.class, chain);
        assertEquals(status, response.getStatus());
        return response.getLocation();
    }

    public DtoRuleChain getChain(URI uri) {
        return getChain(uri, OK.getStatusCode());
    }

    public DtoRuleChain getChain(URI uri, int status) {
        ClientResponse response = resource.uri(uri)
                .type(APPLICATION_CHAIN_JSON).get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        if (status == OK.getStatusCode()) {
            return response.getEntity(DtoRuleChain.class);
        } else {
            return null;
        }
    }
}
