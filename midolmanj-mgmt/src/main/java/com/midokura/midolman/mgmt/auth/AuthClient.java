/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth;

/**
 * Interface for auth Client.
 */
public interface AuthClient {

    /**
     * Validate a token and return a UserIdentity object.
     *
     * @param token
     *            Token to validate.
     * @return UserIdentity object if valid if token is valid, null if invalid.
     */
    public UserIdentity getUserIdentityByToken(String token)
            throws AuthException;

}
