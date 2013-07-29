/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import org.midonet.midolman.state.StateAccessException;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

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

    /**
     * Get a {@link Tenant} object given its ID.
     *
     * @param id Tenant ID
     * @return {@link Tenant} object
     * @throws AuthException
     */
    public Tenant getTenant(String id) throws AuthException;

    /**
     * Get a list of all the Tenant objects in the identity system.
     *
     * @param request Servlet request if additional field is needed to retrieve
     *                tenants.
     * @return List of Tenant objects
     */
    public List<Tenant> getTenants(HttpServletRequest request)
            throws AuthException;
}
