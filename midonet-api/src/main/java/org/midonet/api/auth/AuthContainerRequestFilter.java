/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

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
                .getAttribute(AuthFilter.USER_IDENTITY_ATTR_KEY);
        req.setSecurityContext(new UserIdentitySecurityContext(user));
        return req;
    }
}
