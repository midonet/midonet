/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.api.rest_api;

import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.state.StatePathExceptionBase.NodeInfo;
import org.midonet.midolman.state.StatePathExistsException;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/**
 * WebApplicationException class to represent 409 status.
 */
public class ConflictHttpException extends WebApplicationException {
    private static final long serialVersionUID = 1L;

    /**
     * Create a ConflictHttpException object with no message.
     */
    public ConflictHttpException() {
        this("");
    }

    /**
     * Create a ConflicHttpExcption object with a message.
     *
     * @param message
     *             Error message.
     */
    public ConflictHttpException(String message) {
        super(ResponseUtils.buildErrorResponse(
                Response.Status.CONFLICT.getStatusCode(), message));
    }

    public ConflictHttpException(Throwable throwable, String message) {
        super(throwable,
            ResponseUtils.buildErrorResponse(Response.Status.CONFLICT.getStatusCode(), message));
    }

    public ConflictHttpException(StatePathExistsException ex) {
        this(getMessage(ex));
    }

    private static String getMessage(StatePathExistsException ex) {
        NodeInfo node = ex.getNodeInfo();
        return MessageProperty.getMessage(MessageProperty.RESOURCE_EXISTS,
                                          node.nodeType.name, node.id);
    }

}
