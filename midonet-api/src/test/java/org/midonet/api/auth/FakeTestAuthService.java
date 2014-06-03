/*
 * Copyright 2014 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import java.util.List;
import javax.servlet.http.HttpServletRequest;

/**
 * This class is just for testing purpose.
 * Used by the {@code TestAuthServiceProvider} to test the fallback mechanism
 */
public class FakeTestAuthService implements AuthService {
    @Override
    public UserIdentity getUserIdentityByToken(String token)
            throws AuthException {
        return null;
    }

    @Override
    public Token login(String username, String password,
                       HttpServletRequest request)
            throws AuthException {
        return null;
    }

    @Override
    public Tenant getTenant(String id) throws AuthException {
        return null;
    }

    @Override
    public List<Tenant> getTenants(HttpServletRequest request)
            throws AuthException {
        return null;
    }
}
