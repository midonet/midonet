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

import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.ws.rs.WebApplicationException;

import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.rest_api.ResponseUtils;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StatePathExceptionBase;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.ResponseUtils.buildErrorResponse;

public class BadRequestHttpException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    public BadRequestHttpException() {
        this("");
    }

    public BadRequestHttpException(String message) {
        super(buildErrorResponse(BAD_REQUEST.getStatusCode(), message));
    }

    public BadRequestHttpException(Throwable e, String message) {
        super(e, buildErrorResponse(BAD_REQUEST.getStatusCode(), message));
    }

    public BadRequestHttpException(Throwable e) {
        super(e);
    }

    public <T> BadRequestHttpException(Set<ConstraintViolation<T>> violations) {
        super(ResponseUtils.buildValidationErrorResponse(violations));
    }

    public BadRequestHttpException(NoStatePathException ex) {
        this(getMessage(ex));
    }

    private static String getMessage(NoStatePathException ex) {
        StatePathExceptionBase.NodeInfo node = ex.getNodeInfo();
        if (node == null) {
            return MessageProperty.getMessage(RESOURCE_NOT_FOUND);
        } else {
            return MessageProperty.getMessage(
                RESOURCE_NOT_FOUND, node.nodeType.name, node.id);
        }
    }
}
