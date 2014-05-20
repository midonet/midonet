/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.NeutronMediaType;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.neutron.NeutronPlugin;
import org.midonet.cluster.data.neutron.Router;
import org.midonet.cluster.data.neutron.RouterInterface;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

import static org.midonet.api.validation.MessageProperty.*;

public class RouterResource extends AbstractNeutronResource {

    private final static Logger log = LoggerFactory.getLogger(
            RouterResource.class);

    @Inject
    public RouterResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context, NeutronPlugin plugin) {
        super(config, uriInfo, context, plugin);
    }

    @POST
    @Consumes(NeutronMediaType.ROUTER_JSON_V1)
    @Produces(NeutronMediaType.ROUTER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(Router router)
            throws SerializationException, StateAccessException {
        log.info("RouterResource.create entered {}", router);

        try {

            Router r = dataClient.createRouter(router);

            log.info("RouterResource.get exiting {}", r);
            return Response.created(
                    NeutronUriBuilder.getRouter(
                            getBaseUri(), r.id)).entity(r).build();

        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("RouterResource.delete entered {}", id);
        dataClient.deleteRouter(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.ROUTER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Router get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("RouterResource.get entered {}", id);

        Router r = dataClient.getRouter(id);
        if (r == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("RouterResource.get exiting {}", r);
        return r;
    }

    @GET
    @Produces(NeutronMediaType.ROUTERS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<Router> list()
            throws SerializationException, StateAccessException {
        log.info("RouterResource.list entered");
        return dataClient.getRouters();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.ROUTER_JSON_V1)
    @Produces(NeutronMediaType.ROUTER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Router router)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("RouterResource.update entered {}", router);

        try {

            Router r = dataClient.updateRouter(id, router);

            log.info("RouterResource.update exiting {}", r);
            return Response.ok(
                    NeutronUriBuilder.getRouter(
                            getBaseUri(), r.id)).entity(r).build();

        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
    }

    @PUT
    @Path("{id}" + NeutronUriBuilder.ADD_ROUTER_INTF)
    @Consumes(NeutronMediaType.ROUTER_INTERFACE_V1)
    @Produces(NeutronMediaType.ROUTER_INTERFACE_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public void addRouterInterface(@PathParam("id") UUID id,
                                   RouterInterface intf)
            throws SerializationException, StateAccessException {
        log.info("RouterResource.addRouterInterface entered {}", intf);

        try {

            RouterInterface ri = dataClient.addRouterInterface(id, intf);
            log.info("RouterResource.addRouterInterface exiting {}", ri);

        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
    }

    @PUT
    @Path("{id}" + NeutronUriBuilder.REMOVE_ROUTER_INTF)
    @Consumes(NeutronMediaType.ROUTER_INTERFACE_V1)
    @Produces(NeutronMediaType.ROUTER_INTERFACE_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public void removeRouterInterface(@PathParam("id") UUID id,
                                      RouterInterface intf)
            throws SerializationException, StateAccessException {
        log.info("RouterResource.removeRouterInterface entered {}", intf);
        RouterInterface ri = dataClient.removeRouterInterface(id, intf);
        log.info("RouterResource.removeRouterInterface exiting {}", ri);
    }
}
