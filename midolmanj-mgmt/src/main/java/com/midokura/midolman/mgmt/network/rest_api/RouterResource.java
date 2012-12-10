/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.ForbiddenHttpException;
import com.midokura.midolman.mgmt.network.Router;
import com.midokura.midolman.mgmt.network.auth.RouterAuthorizer;
import com.midokura.midolman.mgmt.network.rest_api.PortResource.RouterPeerPortResource;
import com.midokura.midolman.mgmt.network.rest_api.PortResource.RouterPortResource;
import com.midokura.midolman.mgmt.network.rest_api.RouteResource.RouterRouteResource;
import com.midokura.midolman.mgmt.rest_api.*;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Root resource class for Virtual Router.
 */
@RequestScoped
public class RouterResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(RouterResource.class);

    private final Authorizer authorizer;
    private final Validator validator;
    private final DataClient dataClient;
    private final ResourceFactory factory;

    @Inject
    public RouterResource(RestApiConfig config, UriInfo uriInfo,
                          SecurityContext context, RouterAuthorizer authorizer,
                          Validator validator, DataClient dataClient,
                          ResourceFactory factory) {
        super(config, uriInfo, context);
        this.authorizer = authorizer;
        this.validator = validator;
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
            throws StateAccessException, InvalidStateOperationException {

        com.midokura.midonet.cluster.data.Router routerData =
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
            MediaType.APPLICATION_JSON })
    public Router get(@PathParam("id") UUID id)
            throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this router.");
        }

        com.midokura.midonet.cluster.data.Router routerData =
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
     * @returns RouterPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORTS)
    public RouterPortResource getPortResource(@PathParam("id") UUID id) {
        return factory.getRouterPortResource(id);
    }

    /**
     * Route resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterRouteResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.ROUTES)
    public RouterRouteResource getRouteResource(@PathParam("id") UUID id) {
        return factory.getRouterRouteResource(id);
    }

    /**
     * Peer port resource locator for bridges.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PEER_PORTS)
    public RouterPeerPortResource getRouterPeerPortResource(
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
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Router router)
            throws StateAccessException, InvalidStateOperationException {

        router.setId(id);

        Set<ConstraintViolation<Router>> violations = validator.validate(
                router, Router.RouterUpdateGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

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
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_ROUTER_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Router router)
            throws StateAccessException, InvalidStateOperationException {

        Set<ConstraintViolation<Router>> violations = validator.validate(
                router, Router.RouterCreateGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

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
     * Handler to list tenant routers.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of Router objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Router> list(@QueryParam("tenant_id") String tenantId)
            throws StateAccessException {

        if (tenantId == null) {
            throw new BadRequestHttpException(
                    "Currently tenant_id is required for search.");
        }

        // Tenant ID query string is a special parameter that is used to check
        // authorization.
        if (!Authorizer.isAdminOrOwner(context, tenantId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view routers of this request.");
        }

        List<com.midokura.midonet.cluster.data.Router> dataRouters =
                dataClient.routersFindByTenant(tenantId);
        List<Router> routers = new ArrayList<Router>();
        if (dataRouters != null) {
            for (com.midokura.midonet.cluster.data.Router dataRouter :
                    dataRouters) {
                Router router = new Router(dataRouter);
                router.setBaseUri(getBaseUri());
                routers.add(router);
            }
        }
        return routers;
    }

}
