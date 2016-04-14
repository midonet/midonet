/*
 * Copyright 2016 Midokura SARL
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

import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.ws.rs.WebApplicationException;

import static javax.ws.rs.core.Response.Status.NOT_ACCEPTABLE;
import static org.midonet.cluster.rest_api.ResponseUtils.buildErrorResponse;

public class NotAcceptableHttpException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    public NotAcceptableHttpException(String message) {
        super(buildErrorResponse(NOT_ACCEPTABLE.getStatusCode(), message));
    }

    public NotAcceptableHttpException(Throwable e, String message) {
        super(e, buildErrorResponse(NOT_ACCEPTABLE.getStatusCode(), message));
    }

    public NotAcceptableHttpException(Throwable e) {
        super(e);
    }

    public <T> NotAcceptableHttpException(Set<ConstraintViolation<T>> violations) {
        super(ResponseUtils.buildValidationErrorResponse(violations));
    }
}
