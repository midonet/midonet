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
package org.midonet.cluster.auth.keystone.v2_0;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.DefaultClientConfig;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.auth.KeystoneAccess;
import org.midonet.cluster.auth.KeystoneAuthCredentials;
import org.midonet.cluster.auth.KeystoneTenant;
import org.midonet.cluster.auth.KeystoneTenantList;
import org.midonet.cluster.package$;

/**
 * Keystone Client V2.0
 */
public class KeystoneClient {

    private final static Logger log = LoggerFactory
            .getLogger(package$.MODULE$.keystoneLog());

    private final KeystoneConfig config;

    public static final String KEYSTONE_TOKEN_HEADER_KEY = "X-Auth-Token";
    public static final String MARKER_QUERY = "marker";
    public static final String LIMIT_QUERY = "limit";

    private final DefaultClientConfig clientConfig = new DefaultClientConfig();

    public KeystoneClient(KeystoneConfig config) {
        this.config = config;

        JacksonJsonProvider jsonProvider = new JacksonJaxbJsonProvider();
        jsonProvider.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                               false);

        this.clientConfig.getSingletons().add(jsonProvider);
    }

    public String getServiceUrl() {
        return config.protocol() + "://" + config.host() + ":" +
               Integer.toString(config.port()) + "/v2.0";
    }

    private String getTokensUrl() {
        return getServiceUrl() + "/tokens";
    }

    private String getTokensUrl(String token) {
        return getTokensUrl() + "/" + token;
    }

    private String getTenantsUrl() {
        return getServiceUrl() + "/tenants";
    }

    private String getTenantUrl(String id) {
        return getTenantsUrl() + "/" + id;
    }

    private <T> T sendGetRequest(WebResource resource, Class<T> clazz)
            throws KeystoneServerException, KeystoneConnectionException {

        try {
            return resource.accept(MediaType.APPLICATION_JSON)
                    .header(KEYSTONE_TOKEN_HEADER_KEY, config.adminToken())
                    .get(clazz);
        } catch (UniformInterfaceException e) {
            throw new KeystoneServerException("Keystone server error.", e,
                    e.getResponse().getStatus());
        } catch (ClientHandlerException e) {
            throw new KeystoneConnectionException(
                    "Could not connect to Keystone server " + resource.getURI(),
                    e);
        }
    }


    public KeystoneAccess createToken(KeystoneAuthCredentials credentials)
            throws KeystoneServerException, KeystoneConnectionException,
                   KeystoneUnauthorizedException {

        Client client = Client.create(clientConfig);
        WebResource resource = client.resource(getTokensUrl());

        try {
            return resource.type(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .post(KeystoneAccess.class, credentials);
        } catch (UniformInterfaceException e) {
            if (e.getResponse().getStatus()
                == Response.Status.BAD_REQUEST.getStatusCode()) {
                String err = "Keystone v2 login credentials invalid " +
                             credentials.toString();
                log.warn(err);
                throw new KeystoneUnauthorizedException(err, e);
            } else if (e.getResponse().getStatus()
                == Response.Status.UNAUTHORIZED.getStatusCode()) {
                String err = "Keystone v2 login credentials not found " +
                             credentials.toString();
                log.warn(err,credentials);
                throw new KeystoneUnauthorizedException(err, e);
            } else {
                throw new KeystoneServerException("Keystone server error", e,
                    e.getResponse().getStatus());
            }
        } catch (ClientHandlerException e) {
            throw new KeystoneConnectionException(
                    "Could not connect to Keystone server. Uri="
                            + resource.getURI(), e);
        }
    }

    public KeystoneAccess getToken(String token) throws KeystoneServerException,
            KeystoneConnectionException {

        Client client = Client.create(clientConfig);
        WebResource resource = client.resource(getTokensUrl(token));

        try {
            return sendGetRequest(resource, KeystoneAccess.class);
        } catch (KeystoneServerException ex) {
            if (ex.getStatus() == Response.Status.NOT_FOUND
                    .getStatusCode()) {
                // This indicates that the token was not found
                log.warn("Keystone v2 token not found {}", token);
                return null;
            }

            throw ex;
        }
    }

    /**
     * Retrieves from Keystone server a tenant given its ID.
     *
     * @param id  ID of the tenant
     * @return {@link KeystoneTenant} object
     * @throws KeystoneServerException
     * @throws KeystoneConnectionException
     */
    public KeystoneTenant getTenant(String id) throws KeystoneServerException,
            KeystoneConnectionException {
        log.debug("Keystone v2 get tenant {}", id);

        Client client = Client.create(clientConfig);
        WebResource resource = client.resource(getTenantUrl(id));

        try {
            return sendGetRequest(resource, KeystoneTenant.class);
        } catch (KeystoneServerException ex) {
            if (ex.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                // This indicates that the tenant was not found.  Not an error
                // from MidoNet.
                log.info("Keystone v2 tenant not found {}", id);
                return null;
            }

            throw ex;
        }
    }

    /**
     * Retrieves from Keystone server a list of tenants from the given optional
     * marker and limit values.  The marker and limit values are appended to
     * the actual HTTP request to Keystone as query string parameters.  The
     * return object of {@link KeystoneTenantList} represents the response body
     * from the Keystone API containing the tenant list.
     *
     * @param marker The ID of the tenant fetched in the previous request.
     *               The returned list will start from the tenant after the
     *               tenant with this ID.  A null and empty values are ignored.
     * @param limit  The number of tenants to fetch.  Must be greater than 0.
     *               This field is ignored if the value is less than 1.
     * @return {@link KeystoneTenantList} object, representing the response from
     *         the Keystone API.
     * @throws KeystoneServerException
     * @throws KeystoneConnectionException
     */
    public KeystoneTenantList getTenants(String marker, int limit)
            throws KeystoneServerException, KeystoneConnectionException {
        log.debug("KeystoneClient.getTenants: entered marker=" + marker +
                ", limit=" + limit);

        Client client = Client.create(clientConfig);
        WebResource resource = client.resource(getTenantsUrl());

        if (!StringUtils.isEmpty(marker)) {
            resource = resource.queryParam(MARKER_QUERY, marker);
        }

        if (limit > 0) {
            resource = resource.queryParam(LIMIT_QUERY,
                    Integer.toString(limit));
        }

        return sendGetRequest(resource, KeystoneTenantList.class);
    }
}
