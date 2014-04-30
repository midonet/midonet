package org.midonet.api.rest_api;

import javax.ws.rs.WebApplicationException;

/**
 * WebApplicationException class to represent 504 status. Thrown when
 * an upstream service is not accessible.
 */
public class GatewayTimeoutHttpException extends WebApplicationException {
    private static final long serialVersionUID = 1L;

    public GatewayTimeoutHttpException(String message) {
        super(ResponseUtils.buildErrorResponse(504, message));
    }
}
