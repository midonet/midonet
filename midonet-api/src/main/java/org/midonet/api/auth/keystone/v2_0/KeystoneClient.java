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

    public KeystoneAccess createToken(KeystoneAuthCredentials credentials)
            throws KeystoneServerException, KeystoneConnectionException,
            KeystoneBadCredsException {

        Client client = Client.create();
        String uri = getTokensUrl();
        WebResource resource = client.resource(uri);

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
                    "Could not connect to Keystone server. Uri=" + uri, e);
        }
    }

    public KeystoneAccess getToken(String token) throws KeystoneServerException,
            KeystoneConnectionException {

        Client client = Client.create();
        String uri = getTokensUrl(token);
        WebResource resource = client.resource(uri);

        try {
            return resource.accept(MediaType.APPLICATION_JSON)
                    .header(KEYSTONE_TOKEN_HEADER_KEY, this.adminToken)
                    .get(KeystoneAccess.class);
        } catch (UniformInterfaceException e) {
            if (e.getResponse().getStatus() == Response.Status.NOT_FOUND
                    .getStatusCode()) {
                // This indicates that the token was not found
                log.warn("KeystoneClient: Token not found {}", token);
                return null;
            }
            throw new KeystoneServerException("Keystone server error.", e,
                    e.getResponse().getStatus());
        } catch (ClientHandlerException e) {
            throw new KeystoneConnectionException(
                    "Could not connect to Keystone server. Uri=" + uri, e);
        }

    }
}
