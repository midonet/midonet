package com.midokura.midolman.mgmt.rest_api.v1.resources;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.midokura.midolman.mgmt.data.dto.ErrorEntity;

public class UnknownRestApiException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    public UnknownRestApiException() {
        super(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }

    private static ErrorEntity buildErrorEntity(String message) {
        ErrorEntity error = new ErrorEntity();
        error.setCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        error.setMessage(message);
        return error;
    }

    public UnknownRestApiException(String message) {
        this(null, message);
    }

    public UnknownRestApiException(Throwable e) {
        this(e, "Unknown Error has occurred.");
    }

    public UnknownRestApiException(Throwable e, String message) {
        super(e, Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(
                buildErrorEntity(message)).type(MediaType.APPLICATION_JSON)
                .build());
    }

}
