package org.midonet.api.rest_api;

import javax.ws.rs.WebApplicationException;

/**
 * WebApplicationException class to represent 504 status. Thrown when
 * an upstream service returns an invalid response.
 */
public class BadGatewayHttpException  extends WebApplicationException {
    private static final long serialVersionUID = 1L;

    public BadGatewayHttpException(String message) {
        super(ResponseUtils.buildErrorResponse(502, message));
    }
}

