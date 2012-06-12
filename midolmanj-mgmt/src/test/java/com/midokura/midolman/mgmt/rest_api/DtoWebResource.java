/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CREATED;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.NO_CONTENT;
import static javax.ws.rs.core.Response.Status.OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.net.URI;

import com.midokura.midolman.mgmt.data.dto.client.DtoError;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

/**
 * Wrapper class for WebResource that provides helpful HTTP methods to be used
 * in tests.
 */
public class DtoWebResource {

    private final WebResource resource;

    public DtoWebResource(WebResource resource) {
        this.resource = resource;
    }

    public WebResource getWebResource() {
        return this.resource;
    }

    public ClientResponse getAndVerifyStatus(URI uri, String mediaType,
            int status) {
        ClientResponse response = resource.uri(uri).type(mediaType)
                .get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        return response;
    }

    public ClientResponse postAndVerifyStatus(URI uri, String mediaType,
            Object object, int status) {
        ClientResponse response = resource.uri(uri).type(mediaType)
                .post(ClientResponse.class, object);
        assertEquals(status, response.getStatus());
        return response;
    }

    public ClientResponse putAndVerifyStatus(URI uri, String mediaType,
            Object object, int status) {
        ClientResponse response = resource.uri(uri).type(mediaType)
                .put(ClientResponse.class, object);
        assertEquals(status, response.getStatus());
        return response;
    }

    public ClientResponse deleteAndVerifyStatus(URI uri, String mediaType,
            int status) {
        ClientResponse response = resource.uri(uri).type(mediaType)
                .delete(ClientResponse.class);
        assertEquals(status, response.getStatus());
        return response;
    }

    public <T> T getAndVerifyOk(URI uri, String mediaType, Class<T> clazz) {
        ClientResponse resp = getAndVerifyStatus(uri, mediaType,
                OK.getStatusCode());
        return resp.getEntity(clazz);
    }

    public DtoError getAndVerifyNotFound(URI uri, String mediaType) {
        ClientResponse resp = getAndVerifyStatus(uri, mediaType,
                NOT_FOUND.getStatusCode());
        return resp.getEntity(DtoError.class);
    }

    public <T> T postAndVerifyCreated(URI uri, String mediaType, Object object,
            Class<T> clazz) {
        ClientResponse resp = postAndVerifyStatus(uri, mediaType, object,
                CREATED.getStatusCode());
        assertNotNull(resp.getLocation());
        return getAndVerifyOk(resp.getLocation(), mediaType, clazz);
    }

    public DtoError postAndVerifyBadRequest(URI uri, String mediaType,
            Object object) {
        ClientResponse resp = postAndVerifyStatus(uri, mediaType, object,
                BAD_REQUEST.getStatusCode());
        return resp.getEntity(DtoError.class);
    }

    public <T> T putAndVerifyNoContent(URI uri, String mediaType,
            Object object, Class<T> clazz) {
        putAndVerifyStatus(uri, mediaType, object, NO_CONTENT.getStatusCode());
        return getAndVerifyOk(uri, mediaType, clazz);
    }

    public DtoError putAndVerifyBadRequest(URI uri, String mediaType,
            Object object) {
        ClientResponse resp = putAndVerifyStatus(uri, mediaType, object,
                BAD_REQUEST.getStatusCode());
        return resp.getEntity(DtoError.class);
    }

    public void deleteAndVerifyNoContent(URI uri, String mediaType) {
        deleteAndVerifyStatus(uri, mediaType, NO_CONTENT.getStatusCode());
        getAndVerifyNotFound(uri, mediaType);
    }

    public DtoError deleteAndVerifyBadRequest(URI uri, String mediaType) {
        ClientResponse resp = deleteAndVerifyStatus(uri, mediaType,
                BAD_REQUEST.getStatusCode());
        return resp.getEntity(DtoError.class);
    }

}
