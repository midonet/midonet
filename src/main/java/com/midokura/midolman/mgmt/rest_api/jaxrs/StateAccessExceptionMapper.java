/*
 * @(#)StateAccessExceptionMapper        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import com.midokura.midolman.mgmt.data.dto.ErrorEntity;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access exception class.
 *
 * @version 1.6 22 Nov 2011
 * @author Ryu Ishimoto *
 */
@Provider
public class StateAccessExceptionMapper implements
        ExceptionMapper<StateAccessException> {

    /* (non-Javadoc)
     * @see javax.ws.rs.ext.ExceptionMapper#toResponse(java.lang.Throwable)
     */
    @Override
    public Response toResponse(StateAccessException e) {
        ErrorEntity error = new ErrorEntity();
        error.setCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        error.setMessage("Data access error: " + e.getMessage());
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(error).type(MediaType.APPLICATION_JSON).build();
    }

}
