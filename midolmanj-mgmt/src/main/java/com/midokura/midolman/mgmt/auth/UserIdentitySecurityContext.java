/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth;

import java.security.Principal;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

/**
 * Security Context wrapper that uses UserIdentity class.
 */
public class UserIdentitySecurityContext implements SecurityContext {

    private Principal principal = null;
    private UserIdentity userIdentity = null;

    @Context
    UriInfo uriInfo;

    /**
     * Constructor
     *
     * @param userIdentity
     *            UserIdentity object.
     */
    public UserIdentitySecurityContext(final UserIdentity userIdentity) {
        if (userIdentity != null) {
            principal = new Principal() {
                @Override
                public String getName() {
                    return userIdentity.getTenantId();
                }
            };
        }
        this.userIdentity = userIdentity;
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.ws.rs.core.SecurityContext#getAuthenticationScheme()
     */
    @Override
    public String getAuthenticationScheme() {
        return "token";
    }

    /**
     * @return The UserIdentity object.
     */
    public UserIdentity getUserIdentity() {
        return userIdentity;
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.ws.rs.core.SecurityContext#getUserPrincipal()
     */
    @Override
    public Principal getUserPrincipal() {
        return principal;
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.ws.rs.core.SecurityContext#isSecure()
     */
    @Override
    public boolean isSecure() {
        // return "https".equals(uriInfo.getRequestUri().getScheme());
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.ws.rs.core.SecurityContext#isUserInRole(java.lang.String)
     */
    @Override
    public boolean isUserInRole(String role) {
        if (userIdentity == null) {
            return false;
        }
        return userIdentity.hasRole(role);
    }

    /**
     * @param userIdentity
     *            　UserIdentity object to set.
     */
    public void setUserIdentity(UserIdentity userIdentity) {
        this.userIdentity = userIdentity;
    }

}
