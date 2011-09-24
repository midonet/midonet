/*
 * @(#)KeystoneClient.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.auth;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;

/**
 * Client for Keystone.
 * 
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
public final class KeystoneClient {
    /*
     * Implements Keystone authentication methods.
     */

    private final static Logger log = LoggerFactory
            .getLogger(KeystoneClient.class);

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
    public KeystoneClient(String protocol, String host, int port) {
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
    public void setAdminToken(String token) {
        this.adminToken = token;
    }

    /**
     * Get the admin token.
     * 
     * @return The admin token.
     */
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
    public boolean validateToken(String token) throws IOException {
        // Validate by sending an HTTP request to Keystone server.

        if (token == null) {
            throw new NullPointerException(token);
        }

        KeystoneJsonParser parser = new KeystoneJsonParser();
        String url = new StringBuilder(this.serviceUrl).append("/tokens/")
                .append(token).toString();
        Client client = Client.create();
        WebResource resource = client.resource(url);
        String response = null;
        try {
            response = resource.accept("text/json").header("X-Auth-Token",
                    this.adminToken).get(String.class);
        } catch (UniformInterfaceException e) {
            // Invalid token.
            return false;
        }

        // TODO: Remove this later
        log.info("Got " + response);
        parser.parse(response);
        log.info("Token = " + parser.getToken());
        log.info("TokenExp = " + parser.getTokenExpiration());
        log.info("TokenTenant = " + parser.getTokenTenant());
        log.info("User = " + parser.getUser());
        log.info("UserTenant = " + parser.getUserTenant());
        String[] roles = parser.getUserRoles();
        for (int i = 0; i < roles.length; i++)
            System.out.println("UserRole = " + roles[i]);

        return true;
    }
}
