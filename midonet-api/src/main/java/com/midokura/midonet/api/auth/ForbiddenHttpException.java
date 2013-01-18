/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.auth;

import com.midokura.midonet.api.rest_api.ResponseUtils;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/**
 * WebApplicationException class to represent 403 status.
 */
public class ForbiddenHttpException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a ForbiddenHttpException object with no message.
     */
    public ForbiddenHttpException() {
        this("");
    }

    /**
     * Create a ForbiddenHttpException object with a message.
     *
     * @param message
     *            Error message.
     */
    public ForbiddenHttpException(String message) {
        super(ResponseUtils.buildErrorResponse(
                Response.Status.FORBIDDEN.getStatusCode(), message));
    }
}
