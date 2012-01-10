/*
 * @(#)TenantUserSecurityContext        1.6 12/1/8
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.auth;

import java.security.Principal;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

/**
 * Security Context wrapper that uses TenantUser class.
 *
 * @version 1.6 8 Jan 2012
 * @author Ryu Ishimoto
 */
public class TenantUserSecurityContext implements SecurityContext {

    private Principal principal = null;
    private TenantUser tenantUser = null;

    @Context
    UriInfo uriInfo;

    /**
     * Constructor
     *
     * @param tenantUser
     *            TenantUser object.
     */
    public TenantUserSecurityContext(final TenantUser tenantUser) {
        if (tenantUser != null) {
            principal = new Principal() {
                @Override
                public String getName() {
                    return tenantUser.getTenantId();
                }
            };
        }
        this.tenantUser = tenantUser;
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.ws.rs.core.SecurityContext#getAuthenticationScheme()
     */
    @Override
    public String getAuthenticationScheme() {
        return "Keystone";
    }

    /**
     * @return The TenantUser object.
     */
    public TenantUser getTenantUser() {
        return tenantUser;
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
        if (tenantUser == null) {
            return false;
        }
        return tenantUser.isRole(role);
    }

    /**
     * @param tenantUser
     *            　TenantUser object to set.
     */
    public void setTenantUser(TenantUser tenantUser) {
        this.tenantUser = tenantUser;
    }

}
