package com.midokura.midolman.mgmt.auth;

import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;

public class KeystoneAuthFilter implements ContainerRequestFilter {

    @Override
    public ContainerRequest filter(ContainerRequest req) {
        Authorizer context = new Authorizer();
        String token = req.getHeaderValue("HTTP_X_AUTH_TOKEN"); // Get token
        context.setToken(token);
        req.setSecurityContext(context);
        return req;
    }

}
