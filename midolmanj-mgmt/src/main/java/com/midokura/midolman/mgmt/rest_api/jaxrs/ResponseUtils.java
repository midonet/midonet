/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

import javax.ws.rs.core.Response;

import com.midokura.midolman.mgmt.data.dto.ErrorEntity;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;

/**
 * Utility methods for Response class.
 */
public class ResponseUtils {

    /**
     * Generate a Response object with the body set to ErrorEntity.
     *
     * @param status
     *            HTTP status to set
     * @param message
     *            Error message
     * @return Response object.
     */
    public static Response buildErrorResponse(int status, String message) {
        ErrorEntity error = new ErrorEntity();
        error.setCode(status);
        error.setMessage(message);
        return Response.status(status).entity(error)
                .type(VendorMediaType.APPLICATION_ERROR_JSON).build();
    }
}
