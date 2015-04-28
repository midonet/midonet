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

import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StatePathExceptionBase.NodeInfo;

import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static org.midonet.cluster.rest_api.ResponseUtils.buildErrorResponse;

public class NotFoundHttpException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a NotFoundHttpException object with no message.
     */
    public NotFoundHttpException() {
        this("");
    }

    public NotFoundHttpException(String message) {
        super(buildErrorResponse(NOT_FOUND.getStatusCode(), message));
    }

    public NotFoundHttpException(Throwable throwable, String message) {
        super(throwable,
            buildErrorResponse(NOT_FOUND.getStatusCode(), message));
    }

    public NotFoundHttpException(NoStatePathException ex) {
        this(getMessage(ex));
    }

    private static String getMessage(NoStatePathException ex) {
        NodeInfo node = ex.getNodeInfo();
        return MessageProperty.getMessage(MessageProperty.RESOURCE_NOT_FOUND,
                                          node.nodeType.name, node.id);
    }
}
