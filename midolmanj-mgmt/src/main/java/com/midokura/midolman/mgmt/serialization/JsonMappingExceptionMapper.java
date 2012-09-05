/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.serialization;

import com.midokura.midolman.mgmt.rest_api.ResponseUtils;
import org.codehaus.jackson.map.JsonMappingException;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

/**
 * ExceptionMapper provider class to handle JsonMappingException.
 */
@Provider
public class JsonMappingExceptionMapper implements
        ExceptionMapper<JsonMappingException> {

    @Override
    public Response toResponse(JsonMappingException e) {
        return ResponseUtils.buildErrorResponse(
                Response.Status.BAD_REQUEST.getStatusCode(),
                "Invalid fields or values were passed in that could not be" +
                        " deserialized into a known object.  Please check" +
                        " fields such as 'type'.");
    }
}
