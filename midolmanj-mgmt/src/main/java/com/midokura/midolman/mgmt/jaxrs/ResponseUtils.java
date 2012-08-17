/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.jaxrs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.ws.rs.core.Response;

import com.midokura.midolman.mgmt.data.dto.ErrorEntity;
import com.midokura.midolman.mgmt.data.dto.ValidationErrorEntity;
import com.midokura.midolman.mgmt.http.VendorMediaType;

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

    /**
     * Generate a validation error message.
     *
     * @param violations
     *            Set of violations.
     * @return Concatenated validation error message.
     */
    public static <T> Response buildValidationErrorResponse(
            Set<ConstraintViolation<T>> violations) {
        ValidationErrorEntity errors = new ValidationErrorEntity();
        errors.setMessage("Validation error(s) found");
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>(
                violations.size());
        for (ConstraintViolation<T> c : violations) {
            Map<String, String> msg = new HashMap<String, String>(2);
            msg.put("property", c.getPropertyPath().toString());
            msg.put("message", c.getMessage());
            messages.add(msg);
        }
        errors.setViolations(messages);
        return Response.status(Response.Status.BAD_REQUEST).entity(errors)
                .type(VendorMediaType.APPLICATION_ERROR_JSON).build();
    }
}
