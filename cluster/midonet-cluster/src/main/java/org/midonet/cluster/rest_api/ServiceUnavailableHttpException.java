/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.rest_api;


import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import com.google.common.collect.ImmutableMap;

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
     * Will set the default retry_after value.
     */
    public ServiceUnavailableHttpException(String message) {
        this(message, RETRY_AFTER_HEADER_DEFAULT_VALUE);
    }

}
