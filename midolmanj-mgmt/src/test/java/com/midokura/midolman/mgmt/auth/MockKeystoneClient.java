/*
 * @(#)KeystoneClientImpl.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.auth;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for Keystone.
 *
 * @version 1.6 29 Mar 2012
 * @author Yoshi Tamura
 */
public final class MockKeystoneClient implements KeystoneClient {

    private final static Logger log = LoggerFactory
            .getLogger(MockKeystoneClient.class);

    private String serviceUrl = null;
    private String adminToken = null; // Token used to talk to Keystone.

    /**
     * Default constructor for KeystoneClient.
     *
     * @param protocol
     *            Protocol to use to access Keystone server.
     * @param host
     *            Keystone server host.
     * @param port
     *            Keystone server port.
     */
    public MockKeystoneClient(String protocol, String host, int port) {
        log.debug("Creating KeystoneClient for protocol {} host {} port {}",
                  new Object[] { protocol, host, port });
        // Set the Keystone service URL.
        this.serviceUrl = new StringBuilder(protocol).append("://")
            .append(host).append(":").append(Integer.toString(port))
            .append("/v2.0").toString();
    }

    /**
     * Set the admin token.
     *
     * @param token
     *            Token to set as the admin token.
     */
    @Override
    public void setAdminToken(String token) {
        this.adminToken = token;
    }

    /**
     * Get the admin token.
     *
     * @return The admin token.
     */
    @Override
    public String getAdminToken() {
        return this.adminToken;
    }

    /**
     * Validate a token via Keystone.
     *
     * @param token
     *            Token to validate.
     * @return True if token is valid, False if invalid.
     * @throws IOException
     *             IO error while getting Keystone response.
     */
    @Override
    public TenantUser getTenantUser(String token) throws IOException {
        log.debug("Validating token " + token);
        if (token == null) {
            throw new NullPointerException(token);
        }

        String url = new StringBuilder(this.serviceUrl).append("/tokens/")
            .append(token).toString();
        String response = "{\"access\": {\"token\": {\"expires\": \"2012-03-29T12:15:12Z\", \"id\": \"999888777666\", \"tenant\": {\"enabled\": true, \"description\": null, \"name\": \"demo\", \"id\": \"bf3cc554bcca4b89aab4e383eec3d916\"}}, \"user\": {\"username\": \"admin\", \"roles_links\": [], \"id\": \"eec1b17d499d4ccaad250e49c7bc4c29\", \"roles\": [{\"id\": \"45984bf0c05e4b1389bc8ac7283e3a6f\", \"name\": \"admin\"}], \"name\": \"admin\"}}}";
        return KeystoneJsonParser.parse(response);
    }
}
