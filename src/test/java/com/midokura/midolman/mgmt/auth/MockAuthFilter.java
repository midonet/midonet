package com.midokura.midolman.mgmt.auth;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;

public class MockAuthFilter implements ContainerRequestFilter {

    @Context
    HttpServletRequest hsr;

    @Override
    public ContainerRequest filter(ContainerRequest req) {
        req.setSecurityContext(new MockAuthorizer());
        return req;
    }
}
