/*
 * @(#)StateAccessExceptionMapper        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import com.midokura.midolman.mgmt.data.dto.ErrorEntity;
import com.midokura.midolman.state.StateAccessException;

@Provider
public class StateAccessExceptionMapper implements
        ExceptionMapper<StateAccessException> {

    @Override
    public Response toResponse(StateAccessException e) {
        ErrorEntity error = new ErrorEntity();
        error.setCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        error.setMessage("Data access error: " + e.getMessage());
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(error).type(MediaType.APPLICATION_JSON).build();
    }

}
