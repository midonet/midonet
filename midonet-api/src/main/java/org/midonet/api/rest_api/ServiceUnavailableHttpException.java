/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.rest_api;


import com.google.common.collect.ImmutableMap;
import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.state.l4lb.MappingStatusException;

import java.util.HashMap;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/**
 * WebApplicationException class to represent 503 status. It contains
 * `RetryAfter` value, the decimal interval in seconds after which the failed
 * request in the header. This should be replaced with
 * `javax.ws.rs.ServiceUnavailableException`.
 */
public class ServiceUnavailableHttpException extends WebApplicationException {
    private static final long serialVersionUID = 1L;
    public static final String RETRY_AFTER_HEADER_KEY = "RetryAfter";
    public static final Long RETRY_AFTER_HEADER_DEFAULT_VALUE = 3L;

    /**
     * Create a ServiceUnavailableHttpException object with no message and
     * retryAfter header.
     */
    public ServiceUnavailableHttpException() {
        this("");
    }

    /**
     *  Create a ServiceUnavailableHttpExcetion object with retryAfter in long.
     *
     * @param retryAfter Decimal interval in seconds after which the failed
     *                   request may be retried.
     */
    public ServiceUnavailableHttpException(Long retryAfter) {
        super(ResponseUtils.buildErrorResponse(
                Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), null,
                ImmutableMap.of(RETRY_AFTER_HEADER_KEY, (Object) retryAfter)));
    }

    /**
     * Create a ServiceUnavailableHttpException object with a message.
     *
     * @param message Error message.
     *
     */
    public ServiceUnavailableHttpException(String message) {
        super(ResponseUtils.buildErrorResponse(
                Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), message,
                new HashMap<String, Object>()));
    }

    /**
     * Create a ServiceUnavailableHttpException object with a message and a
     * retryAfter header.
     *
     * @param message Error message.
     * @param retryAfter Decimal interval in seconds after which the failed
     *                   request may be retried.
     */
    public ServiceUnavailableHttpException(String message, Long retryAfter) {
        super(ResponseUtils.buildErrorResponse(
                Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), message,
                ImmutableMap.of(RETRY_AFTER_HEADER_KEY, (Object) retryAfter)));
    }

    /**
     * Create a ServiceUnavailableHttpException object with a message based on
     * the mappingStatus of the pool and a retryAfter header set as the three
     * seconds after this exception is created by default.
     *
     * @param ex MappingStatusException thrown by the data client.
     */
    public ServiceUnavailableHttpException(MappingStatusException ex) {
        this(MessageProperty.getMessage(
                MessageProperty.MAPPING_STATUS_IS_PENDING, ex.getMessage()),
                RETRY_AFTER_HEADER_DEFAULT_VALUE);
    }

    /**
     * Create a ServiceUnavailableHttpException object with a message based on
     * the mappingStatus of the pool and a retryAfter header set as the ten
     * seconds after this exception is created.
     *
     * @param ex MappingStatusException thrown by the data client.
     * @param retryAfter Decimal interval in seconds after which the failed
     *                   request may be retried.
     */
    public ServiceUnavailableHttpException(MappingStatusException ex, Long retryAfter) {
        this(MessageProperty.getMessage(MessageProperty.MAPPING_STATUS_IS_PENDING,
                ex.getMessage()), retryAfter);
    }
}
