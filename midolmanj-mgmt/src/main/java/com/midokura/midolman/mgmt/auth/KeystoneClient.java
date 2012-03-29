/*
 * @(#)KeystoneClient.java        1.6 12/03/29
 *
 * Copyright 2012 Midokura KK
 */

package com.midokura.midolman.mgmt.auth;

import java.io.IOException;

/**
 * Interface for Keystone Client.
 *
 * @version 1.6 29 Mar 2012
 * @author Yoshi Tamura
 */
public interface KeystoneClient {
    /**
     * Set the admin token.
     *
     * @param token
     *            Token to set as the admin token.
     */
    public void setAdminToken(String token);

    /**
     * Get the admin token.
     *
     * @return The admin token.
     */
    public String getAdminToken();

    /**
     * Validate a token via Keystone.
     *
     * @param token
     *            Token to validate.
     * @return True if token is valid, False if invalid.
     * @throws IOException
     *             IO error while getting Keystone response.
     */
    public TenantUser getTenantUser(String token) throws IOException;
}
