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
package org.midonet.api.auth;

import org.midonet.api.rest_api.ResponseUtils;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/**
 * WebApplicationException class to represent 403 status.
 */
public class ForbiddenHttpException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a ForbiddenHttpException object with no message.
     */
    public ForbiddenHttpException() {
        this("");
    }

    /**
     * Create a ForbiddenHttpException object with a message.
     *
     * @param message
     *            Error message.
     */
    public ForbiddenHttpException(String message) {
        super(ResponseUtils.buildErrorResponse(
                Response.Status.FORBIDDEN.getStatusCode(), message));
    }
}
