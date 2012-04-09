/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/**
 * WebApplicationException class to represent 404 status.
 */
public class NotFoundHttpException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a NotFoundHttpException object with no message.
     */
    public NotFoundHttpException() {
        this("");
    }

    /**
     * Create a NotFoundHttpException object with a message.
     *
     * @param message
     *            Error message.
     */
    public NotFoundHttpException(String message) {
        super(ResponseUtils.buildErrorResponse(
                Response.Status.NOT_FOUND.getStatusCode(), message));
    }
}
