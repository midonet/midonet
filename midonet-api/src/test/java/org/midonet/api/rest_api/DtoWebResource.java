/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import org.midonet.client.dto.DtoError;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

import java.net.URI;
import java.util.Map;

import static javax.ws.rs.core.Response.Status.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
        return getAndVerifyStatus(uri, null, mediaType, status);
    }

    public ClientResponse getAndVerifyStatus(URI uri,
                                             Map<String, String> queryStrings,
                                             String mediaType,
                                             int status) {

        WebResource res = resource.uri(uri);
        if (queryStrings != null) {
            for (Map.Entry<String, String> entry : queryStrings.entrySet()) {
                res = res.queryParam(entry.getKey(), entry.getValue());
            }
        }

        ClientResponse response = res
                .accept(mediaType)
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

    public <T> T getAndVerifyOk(URI uri, Map<String, String> queryStrings,
                                String mediaType, Class<T> clazz) {
        ClientResponse resp = getAndVerifyStatus(uri, queryStrings, mediaType,
                OK.getStatusCode());
        return resp.getEntity(clazz);
    }

    public DtoError getAndVerifyNotFound(URI uri, String mediaType) {
        ClientResponse resp = getAndVerifyStatus(uri, mediaType,
                NOT_FOUND.getStatusCode());
        return resp.getEntity(DtoError.class);
    }

    public DtoError getAndVerifyBadRequest(URI uri, String mediaType) {
        ClientResponse resp = getAndVerifyStatus(uri, mediaType,
                BAD_REQUEST.getStatusCode());
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
        return deleteAndVerifyError(
                uri, mediaType, BAD_REQUEST.getStatusCode());
    }

    public DtoError deleteAndVerifyNotFound(URI uri, String mediaType) {
        return deleteAndVerifyError(uri, mediaType, NOT_FOUND.getStatusCode());
    }

    public DtoError deleteAndVerifyError(
            URI uri, String mediaType, int errorCode) {
        ClientResponse resp = deleteAndVerifyStatus(uri, mediaType, errorCode);
        return resp.getEntity(DtoError.class);
    }
}
