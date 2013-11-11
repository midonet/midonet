/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.error;

import org.midonet.api.rest_api.ResponseUtils;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * ExceptionMapper provider class to handle any Throwables.
 */
@Provider
public class ThrowableMapper implements ExceptionMapper<Throwable> {

    private final static Logger log =
        LoggerFactory.getLogger(ThrowableMapper.class);


    /*
     * (non-Javadoc)
     *
     * @see javax.ws.rs.ext.ExceptionMapper#toResponse(java.lang.Throwable)
     */
    @Override
    public Response toResponse(Throwable e) {
        log.error("Encountered uncaught exception: ", e);
        return ResponseUtils.buildErrorResponse(
                Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                "An internal server error has occurred, please try again.");
    }
}
