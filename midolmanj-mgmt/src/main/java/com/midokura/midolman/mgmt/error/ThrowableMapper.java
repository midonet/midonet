/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.error;

import com.midokura.midolman.mgmt.rest_api.ResponseUtils;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

/**
 * ExceptionMapper provider class to handle any Throwables.
 */
@Provider
public class ThrowableMapper implements ExceptionMapper<Throwable> {

    /*
     * (non-Javadoc)
     *
     * @see javax.ws.rs.ext.ExceptionMapper#toResponse(java.lang.Throwable)
     */
    @Override
    public Response toResponse(Throwable e) {
        return ResponseUtils.buildErrorResponse(
                Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                "Unrecoverable server error has occurred.");
    }
}
