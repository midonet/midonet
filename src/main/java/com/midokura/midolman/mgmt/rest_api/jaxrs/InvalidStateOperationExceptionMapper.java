/*
 * @(#)InvalidStateOperationExceptionMapper        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import com.midokura.midolman.mgmt.data.dto.ErrorEntity;
import com.midokura.midolman.state.InvalidStateOperationException;

/**
 * Exception to indicate that an invalid state operation was performed.
 *
 * @version 1.6 22 Nov 2011
 * @author Ryu Ishimoto
 */
@Provider
public class InvalidStateOperationExceptionMapper implements
        ExceptionMapper<InvalidStateOperationException> {

    /* (non-Javadoc)
     * @see javax.ws.rs.ext.ExceptionMapper#toResponse(java.lang.Throwable)
     */
    @Override
    public Response toResponse(InvalidStateOperationException e) {
        ErrorEntity error = new ErrorEntity();
        error.setCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        error.setMessage("Invalid operation: " + e.getMessage());
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(error).type(MediaType.APPLICATION_JSON).build();
    }

}