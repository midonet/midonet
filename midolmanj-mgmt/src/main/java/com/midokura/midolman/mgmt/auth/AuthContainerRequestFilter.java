/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

import com.midokura.midolman.mgmt.servlet.ServletSupport;
import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;

/**
 * Class to implement auth request filter.
 */
public class AuthContainerRequestFilter implements ContainerRequestFilter {

    @Context
    HttpServletRequest hsr;

    /*
     * (non-Javadoc)
     *
     * @see
     * com.sun.jersey.spi.container.ContainerRequestFilter#filter(com.sun.jersey
     * .spi.container.ContainerRequest)
     */
    @Override
    public ContainerRequest filter(ContainerRequest req) {
        UserIdentity user = (UserIdentity) hsr
                .getAttribute(ServletSupport.USER_IDENTITY_ATTR_KEY);
        req.setSecurityContext(new UserIdentitySecurityContext(user));
        return req;
    }
}
