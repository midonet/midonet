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
package org.midonet.api.auth;

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
    UserIdentity getUserIdentityByToken(String token) throws AuthException;

    /**
     * Generate a token from username/password
     *
     * @param username username
     * @param password password
     * @param request Servlet request if additional field is needed for login.
     * @return Token object
     * @throws AuthException
     */
    Token login(String username, String password, HttpServletRequest request)
        throws AuthException;

    /**
     * Get a {@link Tenant} object given its ID.
     *
     * @param id Tenant ID
     * @return {@link Tenant} object
     * @throws AuthException
     */
    Tenant getTenant(String id) throws AuthException;

    /**
     * Get a list of all the Tenant objects in the identity system.
     *
     * @param request Servlet request if additional field is needed to retrieve
     *                tenants.
     * @return List of Tenant objects
     */
    List<Tenant> getTenants(HttpServletRequest request) throws AuthException;
}
