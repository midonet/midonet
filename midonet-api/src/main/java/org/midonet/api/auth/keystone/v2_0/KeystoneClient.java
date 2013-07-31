/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth.keystone.v2_0;

import com.google.inject.Inject;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import org.midonet.api.auth.keystone.KeystoneBadCredsException;
import org.midonet.api.auth.keystone.KeystoneConnectionException;
import org.midonet.api.auth.keystone.KeystoneServerException;
import org.midonet.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * Keystone Client V2.0
 */
public class KeystoneClient {

    private final static Logger log = LoggerFactory
            .getLogger(KeystoneClient.class);

    private final int port;
    private final String protocol;
    private final String host;
    private final String adminToken;

    public static final String KEYSTONE_TOKEN_HEADER_KEY = "X-Auth-Token";
    public static final String MARKER_QUERY = "marker";
    public static final String LIMIT_QUERY = "limit";

    @Inject
    public KeystoneClient(String host, int port, String protocol,
                          String adminToken) {
        this.host = host;
        this.port = port;
        this.protocol = protocol;
        this.adminToken = adminToken;
    }

    /**
     * @return the serviceUrl
     */
    public String getServiceUrl() {
        return new StringBuilder(this.protocol).append("://")
                .append(this.host).append(":").append(
                        Integer.toString(this.port))
                .append("/v2.0").toString();
    }

    /**
     * @return the tokens URL
     */
    private String getTokensUrl() {
        return new StringBuilder(getServiceUrl()).append("/tokens").toString();
    }

    /**
     * @return the tokens URL
     *
     * @param token Token to append to the URL
     */
    private String getTokensUrl(String token) {
        return new StringBuilder(getTokensUrl()).append("/").append(token)
                .toString();
    }

    private String getTenantsUrl() {
        return new StringBuilder(getServiceUrl()).append("/tenants").toString();
    }

    private String getTenantUrl(String id) {
        return new StringBuilder(getTenantsUrl()).append("/").append(
                id).toString();
    }

    private <T> T sendGetRequest(WebResource resource, Class<T> clazz)
            throws KeystoneServerException, KeystoneConnectionException {

        try {
            return resource.accept(MediaType.APPLICATION_JSON)
                    .header(KEYSTONE_TOKEN_HEADER_KEY, this.adminToken)
                    .get(clazz);
        } catch (UniformInterfaceException e) {
            throw new KeystoneServerException("Keystone server error.", e,
                    e.getResponse().getStatus());
        } catch (ClientHandlerException e) {
            throw new KeystoneConnectionException(
                    "Could not connect to Keystone server. Uri="
                            + resource.getURI(), e);
        }
    }


    public KeystoneAccess createToken(KeystoneAuthCredentials credentials)
            throws KeystoneServerException, KeystoneConnectionException,
            KeystoneBadCredsException {

        Client client = Client.create();
        WebResource resource = client.resource(getTokensUrl());

        try {
            return resource.type(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .post(KeystoneAccess.class, credentials);
        } catch (UniformInterfaceException e) {
            if (e.getResponse().getStatus()
                == Response.Status.BAD_REQUEST.getStatusCode()) {
                String err = "KeystoneClient: keystone login creds invalid "
                              + credentials.toString();
                log.warn(err);
                throw new KeystoneBadCredsException(err, e);
            } else if (e.getResponse().getStatus()
                == Response.Status.UNAUTHORIZED.getStatusCode()) {
                String err = "KeystoneClient: keystone login creds not found "
                              + credentials.toString();
                log.warn(err,credentials);
                throw new KeystoneBadCredsException(err, e);
            } else {
                throw new KeystoneServerException("Keystone server error.", e,
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

        Client client = Client.create();
        WebResource resource = client.resource(getTokensUrl(token));

        try {
            return sendGetRequest(resource, KeystoneAccess.class);
        } catch (KeystoneServerException ex) {
            if (ex.getStatus() == Response.Status.NOT_FOUND
                    .getStatusCode()) {
                // This indicates that the token was not found
                log.warn("KeystoneClient: Token not found {}", token);
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
        log.debug("KeystoneClient.getTenant: entered id=" + id);

        Client client = Client.create();
        WebResource resource = client.resource(getTenantUrl(id));

        try {
            return sendGetRequest(resource, KeystoneTenant.class);
        } catch (KeystoneServerException ex) {
            if (ex.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                // This indicates that the tenant was not found.  Not an error
                // from MidoNet.
                log.info("KeystoneClient: Tenant not found {}", id);
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

        Client client = Client.create();
        WebResource resource = client.resource(getTenantsUrl());

        if (!StringUtil.isNullOrEmpty(marker)) {
            resource = resource.queryParam(MARKER_QUERY, marker);
        }

        if (limit > 0) {
            resource = resource.queryParam(LIMIT_QUERY,
                    Integer.toString(limit));
        }

        return sendGetRequest(resource, KeystoneTenantList.class);
    }
}
