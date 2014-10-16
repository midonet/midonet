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
package org.midonet.api.rest_api;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

public class InternalServerErrorHttpException extends WebApplicationException {
    private static final long serialVersionUID = 1L;

    /**
     * Create an InternalServerErrorHttpException object with no message.
     */
    public InternalServerErrorHttpException() {
        this("");
    }

    /**
     * Create an InternalServerErrorHttpException object with a message.
     * @param message The error message.
     */
    public InternalServerErrorHttpException(String message) {
        super(ResponseUtils.buildErrorResponse(
            Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), message));
    }

    /**
     * Create an InternalServerErrorHttpException object with an inner
     * throwable and the specified message.
     * @param throwable The inner throwable.
     * @param message The error message.
     */
    public InternalServerErrorHttpException(
        Throwable throwable, String message) {
        super(throwable, ResponseUtils.buildErrorResponse(
            Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), message));
    }
}
