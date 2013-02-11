/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import javax.servlet.http.HttpServletRequest;

/**
 * Interface for auth service.
 */
public interface AuthService {

    /**
     * Validate a token and return a UserIdentity object.
     *
     * @param token
     *            Token to validate.
     * @return UserIdentity object if valid if token is valid, null if invalid.
     */
    public UserIdentity getUserIdentityByToken(String token)
            throws AuthException;


    /**
     * Generate a token from username/password
     *
     * @param username username
     * @param password password
     * @param request Servlet request if additional field is needed for login.
     * @return Token object
     * @throws AuthException
     */
    public Token login(String username, String password,
                       HttpServletRequest request)
        throws AuthException;
}
