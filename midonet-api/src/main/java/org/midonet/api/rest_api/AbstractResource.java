/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import java.net.URI;
import java.util.Set;
import java.util.UUID;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Addr$;
import static org.midonet.api.validation.MessageProperty.IP_ADDR_INVALID_WITH_PARAM;
import static org.midonet.api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;

/**
 * Base resource class.
 */
public abstract class AbstractResource {

    protected final RestApiConfig config;
    protected final UriInfo uriInfo;
    protected final SecurityContext context;
    protected final Validator validator;
    protected final DataClient dataClient;

    public AbstractResource(RestApiConfig config, UriInfo uriInfo,
                            SecurityContext context, DataClient dataClient) {
        this(config, uriInfo, context, dataClient, null);
    }

    public AbstractResource(RestApiConfig config, UriInfo uriInfo,
                            SecurityContext context, DataClient dataClient,
                            Validator validator) {
        this.config = config;
        this.uriInfo = uriInfo;
        this.context = context;
        this.dataClient = dataClient;
        this.validator = validator;
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

    protected <T> void validate(T apiObj, Class<?>... groups) {
        Set<ConstraintViolation<T>> violations =
                validator.validate(apiObj, groups);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }
    }

    protected IPv4Addr parseIPv4Addr(String ipAddrStr) {
        try {
            return IPv4Addr$.MODULE$.fromString(ipAddrStr);
        } catch (Exception ex) {
            throw new BadRequestHttpException(
                    getMessage(IP_ADDR_INVALID_WITH_PARAM, ipAddrStr));
        }
    }

    protected void throwNotFound(UUID id, String resourceType) {
        throw new NotFoundHttpException(
                getMessage(RESOURCE_NOT_FOUND, resourceType, id));
    }

    protected Bridge getBridgeOrThrow(UUID id, boolean badRequest)
            throws StateAccessException, SerializationException {
        org.midonet.cluster.data.Bridge bridge = dataClient.bridgesGet(id);
        if (bridge == null) {
            String msg = getMessage(RESOURCE_NOT_FOUND, "bridge", id);
            throw badRequest ? new BadRequestHttpException(msg) :
                               new NotFoundHttpException(msg);
        }
        return bridge;
    }
}
