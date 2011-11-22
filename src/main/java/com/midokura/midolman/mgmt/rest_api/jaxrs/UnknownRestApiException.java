/*
 * @(#)UnknownRestApiException        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.midokura.midolman.mgmt.data.dto.ErrorEntity;

/**
 * Exception that could not be handled.
 *
 * @version 1.6 22 Nov 2011
 * @author Ryu Ishimoto *
 */
public class UnknownRestApiException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    /**
     * Default constructor
     */
    public UnknownRestApiException() {
        super(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }

    /**
     * Constructor with an error message parameter.
     *
     * @param message
     *            Exception message
     */
    public UnknownRestApiException(String message) {
        this(null, message);
    }

    /**
     * Constructor with Throwable parameter.
     *
     * @param e
     *            Throwable object passed in from the caller.
     */
    public UnknownRestApiException(Throwable e) {
        this(e, "Unknown Error has occurred.");
    }

    /**
     * Constructor with a Throwable object and an error message.
     *
     * @param e
     *            Throwable object.
     * @param message
     *            Error message.
     */
    public UnknownRestApiException(Throwable e, String message) {
        super(e, Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(buildErrorEntity(message))
                .type(MediaType.APPLICATION_JSON).build());
    }

    /**
     * Construct an ErrorEntity object to contain error information.
     *
     * @param message
     *            Error message.
     * @return ErrorEntity object
     */
    private static ErrorEntity buildErrorEntity(String message) {
        ErrorEntity error = new ErrorEntity();
        error.setCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        error.setMessage(message);
        return error;
    }

}
