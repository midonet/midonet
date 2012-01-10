/*
 * @(#)NoAuthSecurityContext        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.auth;

import java.security.Principal;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

public class NoAuthSecurityContext implements SecurityContext {

    private Principal principal = null;

    @Context
    UriInfo uriInfo;

    public NoAuthSecurityContext() {
        principal = new Principal() {
            @Override
            public String getName() {
                return "Mock";
            }
        };
    }

    @Override
    public Principal getUserPrincipal() {
        return principal;
    }

    @Override
    public boolean isSecure() {
        return true;
    }

    @Override
    public boolean isUserInRole(String role) {
        return true;
    }

    @Override
    public String getAuthenticationScheme() {
        return "Mock";
    }
}
