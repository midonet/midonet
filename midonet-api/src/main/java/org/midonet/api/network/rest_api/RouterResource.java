/*
 * Copyright (c) 2011-2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.network.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.auth.Authorizer;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.network.Router;
import org.midonet.api.network.auth.RouterAuthorizer;
import org.midonet.api.rest_api.*;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Root resource class for Virtual Router.
 */
@RequestScoped
public class RouterResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(RouterResource.class);

    private final RouterAuthorizer authorizer;
    private final DataClient dataClient;
    private final ResourceFactory factory;

    @Inject
    public RouterResource(RestApiConfig config, UriInfo uriInfo,
                          SecurityContext context, RouterAuthorizer authorizer,
                          Validator validator, DataClient dataClient,
                          ResourceFactory factory) {
        super(config, uriInfo, context, validator);
        this.authorizer = authorizer;
        this.dataClient = dataClient;
        this.factory = factory;
    }

    /**
     * Handler to deleting a router.
     *
     * @param id
     *            Router ID from the request.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException,
            SerializationException {

        org.midonet.cluster.data.Router routerData =
                dataClient.routersGet(id);
        if (routerData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this router.");
        }

        dataClient.routersDelete(id);
    }

    /**
     * Handler to getting a router.
     *
     * @param id
     *            Router ID from the request.
     * @throws StateAccessException
     *             Data access error.
     * @return A Router object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTER_JSON,
            VendorMediaType.APPLICATION_ROUTER_JSON_V2,
            MediaType.APPLICATION_JSON })
    public Router get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this router.");
        }

        org.midonet.cluster.data.Router routerData =
                dataClient.routersGet(id);
        if (routerData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        // Convert to the REST API DTO
        Router router = new Router(routerData);
        router.setBaseUri(getBaseUri());

        return router;
    }

    /**
     * Port resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @return RouterPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORTS)
    public PortResource.RouterPortResource getPortResource(@PathParam("id") UUID id) {
        return factory.getRouterPortResource(id);
    }

    /**
     * Route resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @return RouterRouteResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.ROUTES)
    public RouteResource.RouterRouteResource getRouteResource(@PathParam("id") UUID id) {
        return factory.getRouterRouteResource(id);
    }

    /**
     * Peer port resource locator for bridges.
     *
     * @param id
     *            Router ID from the request.
     * @return RouterPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PEER_PORTS)
    public PortResource.RouterPeerPortResource getRouterPeerPortResource(
            @PathParam("id") UUID id) {
        return factory.getRouterPeerPortResource(id);
    }

    /**
     * Handler to updating a router.
     *
     * @param id
     *            Router ID from the request.
     * @param router
     *            Router object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_ROUTER_JSON,
            VendorMediaType.APPLICATION_ROUTER_JSON_V2,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Router router)
            throws StateAccessException,
            SerializationException {

        router.setId(id);

        validate(router, Router.RouterUpdateGroupSequence.class);

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this router.");
        }

        dataClient.routersUpdate(router.toData());
    }

    /**
     * Handler for creating a tenant router.
     *
     * @param router
     *            Router object.
     * @throws StateAccessException
     *             Data access error.
     * @return Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_ROUTER_JSON,
            VendorMediaType.APPLICATION_ROUTER_JSON_V2,
            MediaType.APPLICATION_JSON })
    public Response create(Router router)
            throws StateAccessException,
            SerializationException {

        validate(router, Router.RouterCreateGroupSequence.class);

        if (!Authorizer.isAdminOrOwner(context, router.getTenantId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to add router to this tenant.");
        }

        UUID id = dataClient.routersCreate(router.toData());
        return Response.created(
                ResourceUriBuilder.getRouter(getBaseUri(), id))
                .build();
    }

    /**
     * Handler to list all routers.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of Router objects.
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON,
            VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON_V2,
            MediaType.APPLICATION_JSON })
    public List<Router> list(@QueryParam("tenant_id") String tenantId)
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.Router> dataRouters = (tenantId == null) ?
                dataClient.routersGetAll() :
                dataClient.routersFindByTenant(tenantId);

        List<Router> routers = new ArrayList<>();
        if (dataRouters != null) {
            for (org.midonet.cluster.data.Router dataRouter :
                    dataRouters) {
                Router router = new Router(dataRouter);
                router.setBaseUri(getBaseUri());
                routers.add(router);
            }
        }
        return routers;
    }
}
