/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midonet.client;

import java.net.URI;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.ClientFilter;
import com.sun.jersey.api.client.filter.LoggingFilter;

import com.midokura.midonet.client.exception.HttpBadRequestException;
import com.midokura.midonet.client.exception.HttpForbiddenException;
import com.midokura.midonet.client.exception.HttpInternalServerError;
import com.midokura.midonet.client.exception.HttpNotFoundException;
import com.midokura.midonet.client.exception.HttpUnauthorizedException;
import com.midokura.midonet.client.jaxrs.WildCardJacksonJaxbJsonProvider;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 8/15/12
 * Time: 12:52 PM
 */
public class WebResource {

    private Client client;
    private URI baseUri;
    private ClientFilter clientFilter;

    public WebResource(URI baseUri) {

        ClientConfig cc = new DefaultClientConfig();
        cc.getSingletons().add(new WildCardJacksonJaxbJsonProvider());
        client = Client.create(cc);
        this.baseUri = baseUri;
    }

    private void handleHttpError(ClientResponse r) {
        switch (r.getStatus()) {
            case 400:
                throw new HttpBadRequestException(r);
            case 401:
                throw new HttpUnauthorizedException(r);
            case 403:
                throw new HttpForbiddenException(r);
            case 404:
                throw new HttpNotFoundException(r);
            case 500:
                throw new HttpInternalServerError(r);
        }
        throw new UniformInterfaceException(r);
    }

    public com.sun.jersey.api.client.WebResource resource() {
        return client.resource(baseUri);
    }


    public void enableLogging() {
        if (clientFilter == null) {
            clientFilter = new LoggingFilter();
        }
        client.addFilter(clientFilter);
    }

    public void disableLogging() {
        client.removeFilter(clientFilter);
        clientFilter = null;
    }

    public URI post(URI uri, Object entity, String mediaType) {
        ClientResponse response = resource()
            .uri(uri)
            .type(mediaType)
            .post(ClientResponse.class, entity);

        if (response.getStatus() >= 300) {
            handleHttpError(response);
        }

        return response.getLocation();
    }

    public URI put(URI uri, Object entity, String mediaType) {
        ClientResponse response = resource()
            .uri(uri)
            .type(mediaType)
            .put(ClientResponse.class, entity);

        if (response.getStatus() != 204 && response.getStatus() != 200) {
            handleHttpError(response);
        }
        return response.getLocation();
    }

    public <T> T get(String path, Class<T> clazz, String mediaType) {
        return makeResource(path).type(mediaType).get(clazz);
    }


    public <T> T get(URI uri, MultivaluedMap<String, String> queryParams,
                     Class<T> clazz, String mediaType) {
        if (uri == null)
            throw new IllegalArgumentException(
                "The URI can't be null. This usually means that a previous "
                    + "call to Mgmt REST api failed.");

        com.sun.jersey.api.client.WebResource resource = resource();
        if (queryParams != null) {
            resource = resource.queryParams(queryParams);
        }
        ClientResponse response = resource.uri(uri)
                                          .type(mediaType)
                                          .accept(mediaType)
                                          .get(ClientResponse.class);
        if (response.getStatus() >= 300) {
            handleHttpError(response);
        }
        return response.getEntity(clazz);
    }

    public void delete(URI uri) {
        try {
            resource().uri(uri).type(MediaType.APPLICATION_JSON).delete();
        } catch (UniformInterfaceException ex) {
            handleHttpError(ex.getResponse());
        }
    }

    private com.sun.jersey.api.client.WebResource makeResource(String path) {
        com.sun.jersey.api.client.WebResource resource = resource();
        UriBuilder b = resource.getUriBuilder();
        if (path.startsWith("/")) {
            b.replacePath(path);
        } else {
            b.path(path);
        }
        URI uri = b.build();
        return resource.uri(uri);
    }

}
