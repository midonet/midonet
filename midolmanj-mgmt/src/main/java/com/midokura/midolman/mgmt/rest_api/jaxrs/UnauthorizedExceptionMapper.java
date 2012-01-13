/*
 * @(#)UnauthorizedExceptionMapper        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.dto.ErrorEntity;

@Provider
public class UnauthorizedExceptionMapper implements
        ExceptionMapper<UnauthorizedException> {

    @Override
    public Response toResponse(UnauthorizedException e) {
        ErrorEntity error = new ErrorEntity();
        error.setCode(Response.Status.UNAUTHORIZED.getStatusCode());
        error.setMessage("Access Denied: " + e.getMessage());
        return Response.status(Response.Status.UNAUTHORIZED).entity(error)
                .type(MediaType.APPLICATION_JSON).build();
    }

}
