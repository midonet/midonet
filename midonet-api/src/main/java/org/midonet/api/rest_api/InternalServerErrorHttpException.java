/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.rest_api;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

public class InternalServerErrorHttpException extends WebApplicationException {
    private static final long serialVersionUID = 1L;

    /**
     * Create an InternalServerErrorHttpException object with no message.
     */
    public InternalServerErrorHttpException() {
        this("");
    }

    /**
     * Create an InternalServerErrorHttpException object with a message.
     * @param message The error message.
     */
    public InternalServerErrorHttpException(String message) {
        super(ResponseUtils.buildErrorResponse(
            Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), message));
    }

    /**
     * Create an InternalServerErrorHttpException object with an inner
     * throwable and the specified message.
     * @param throwable The inner throwable.
     * @param message The error message.
     */
    public InternalServerErrorHttpException(
        Throwable throwable, String message) {
        super(throwable, ResponseUtils.buildErrorResponse(
            Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), message));
    }
}
