/*
 * @(#)KeystoneAuthFilter        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.auth;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;

public class KeystoneAuthFilter implements ContainerRequestFilter {

    @Context
    HttpServletRequest hsr;

    @Override
    public ContainerRequest filter(ContainerRequest req) {
        TenantUser tu = (TenantUser) hsr
                .getAttribute("com.midokura.midolman.mgmt.auth.TenantUser");
        req.setSecurityContext(new TenantUserSecurityContext(tu));
        return req;
    }
}
