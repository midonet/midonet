/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.client;

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

import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.ForbiddenHttpException;
import org.midonet.cluster.rest_api.InternalServerErrorHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.UnauthorizedHttpException;
import org.midonet.cluster.rest_api.jaxrs.WildcardJacksonJaxbJsonProvider;

public class WebResource {

    private Client client;
    private URI baseUri;
    private ClientFilter clientFilter;

    public WebResource(URI baseUri) {

        ClientConfig cc = new DefaultClientConfig();
        cc.getSingletons().add(new WildcardJacksonJaxbJsonProvider(null));
        client = Client.create(cc);
        this.baseUri = baseUri;
    }

    private void handleHttpError(ClientResponse r) {
        switch (r.getStatus()) {
            case 400: throw new BadRequestHttpException(r.toString());
            case 401: throw new UnauthorizedHttpException(r.toString());
            case 403: throw new ForbiddenHttpException(r.toString());
            case 404: throw new NotFoundHttpException(r.toString());
            case 500: throw new InternalServerErrorHttpException(r.toString());
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
        return makeResource(path).type(mediaType).accept(mediaType).get(clazz);
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
