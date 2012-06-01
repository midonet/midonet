/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static javax.ws.rs.core.Response.Status.CREATED;
import static javax.ws.rs.core.Response.Status.NO_CONTENT;
import static javax.ws.rs.core.Response.Status.OK;
import static org.junit.Assert.assertEquals;

import java.net.URI;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

public class DtoWebResource {

    private final WebResource resource;

    public DtoWebResource(WebResource resource) {
        this.resource = resource;
    }

    public WebResource getWebResource() {
        return resource;
    }

    public URI post(URI uri, String mediaType, Object object) {
        return post(uri, mediaType, object, CREATED.getStatusCode());
    }

    public URI post(URI uri, String mediaType, Object object, int status) {
        ClientResponse response = resource.uri(uri).type(mediaType)
                .post(ClientResponse.class, object);
        assertEquals(status, response.getStatus());
        return response.getLocation();
    }

    public URI put(URI uri, String mediaType, Object object) {
        return put(uri, mediaType, object, OK.getStatusCode());
    }

    public URI put(URI uri, String mediaType, Object object, int status) {
        ClientResponse response = resource.uri(uri).type(mediaType)
                .put(ClientResponse.class, object);
        assertEquals(status, response.getStatus());
        return response.getLocation();
    }

    public <T> T get(URI uri, String mediaType, Class<T> clazz) {
        return get(uri, mediaType, clazz, OK.getStatusCode());
    }

    public <T> T get(URI uri, String mediaType, Class<T> clazz, int status) {
        ClientResponse response = resource.uri(uri).type(mediaType)
                .get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        if (status == OK.getStatusCode()) {
            return response.getEntity(clazz);
        } else {
            return null;
        }
    }

    public void delete(URI uri, String mediaType) {
        delete(uri, mediaType, NO_CONTENT.getStatusCode());
    }

    public void delete(URI uri, String mediaType, int status) {
        ClientResponse response = resource.uri(uri).type(mediaType)
                .delete(ClientResponse.class);
        assertEquals(status, response.getStatus());
    }
}
