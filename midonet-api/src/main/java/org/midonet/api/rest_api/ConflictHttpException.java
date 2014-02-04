/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.rest_api;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/**
 * WebApplicationException class to represent 409 status.
 */
public class ConflictHttpException extends WebApplicationException {
    private static final long serialVersionUID = 1L;

    /**
     * Create a ConflictHttpException object with no message.
     */
    public ConflictHttpException() {
        this("");
    }

    /**
     * Create a ConflicHttpExcption object with a message.
     *
     * @param message
     *             Error message.
     */
    public ConflictHttpException(String message) {
        super(ResponseUtils.buildErrorResponse(
                Response.Status.CONFLICT.getStatusCode(), message));
    }

}
