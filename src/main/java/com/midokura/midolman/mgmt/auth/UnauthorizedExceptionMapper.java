package com.midokura.midolman.mgmt.auth;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

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
