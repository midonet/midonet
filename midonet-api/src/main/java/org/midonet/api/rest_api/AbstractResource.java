/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StatePathExceptionBase.NodeType;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.UUID;

/**
 * Base resource class.
 */
public abstract class AbstractResource {

    protected final RestApiConfig config;
    protected final UriInfo uriInfo;
    protected final SecurityContext context;

    public AbstractResource(RestApiConfig config, UriInfo uriInfo,
                            SecurityContext context) {
        this.config = config;
        this.uriInfo = uriInfo;
        this.context = context;
    }

    /**
     * @return The URI specified in the configuration file.  If not set, then
     * the base URI from the current request is returned.
     */
    public URI getBaseUri() {
        if (config.getBaseUri() == null || config.getBaseUri().equals("")) {
            return uriInfo.getBaseUri();
        } else {
            return UriBuilder.fromUri(config.getBaseUri()).build();
        }
    }

    /**
     * Returns either a BadRequestHttpException or a NotFoundHttpException,
     * depending on the NoStatePathException's node ID.
     *
     * The use case is that we want to throw a Not Found exception if the
     * API call's primary resource cannot be found, or a Bad Request
     * exception if the primary resource refers to another resource that
     * cannot be found.
     *
     * For example, when updating a Router, we want to throw a Not Found
     * exception if the Router ID in the URI through which the caller
     * invoked the API is invalid. But if the caller tries to update a
     * Router's load balancer ID and no such load balancer exists, we
     * should throw a Bad Request exception because the load balancer ID
     * is not part of the invoking URI, only the data passed in.
     *
     * Returns exception rather than throwing, because the compiler won't
     * recognize that it always throws.
     */
    protected WebApplicationException badReqOrNotFoundException(
            NoStatePathException ex, UUID primaryResourceId) {
        if (primaryResourceId.equals(ex.getNodeInfo().id)) {
            return new NotFoundHttpException(ex);
        } else {
            return new BadRequestHttpException(ex);
        }
    }
}
