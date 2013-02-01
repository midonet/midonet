/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

/**
 * ExceptionMapper provider class to handle WebApplicationException.
 */
@Provider
public class WebApplicationExceptionMapper implements
        ExceptionMapper<WebApplicationException> {

    /*
     * (non-Javadoc)
     *
     * @see javax.ws.rs.ext.ExceptionMapper#toResponse(java.lang.Throwable)
     */
    @Override
    public Response toResponse(WebApplicationException e) {
        return ResponseUtils.buildErrorResponse(e.getResponse().getStatus(),
                e.getMessage());
    }
}
