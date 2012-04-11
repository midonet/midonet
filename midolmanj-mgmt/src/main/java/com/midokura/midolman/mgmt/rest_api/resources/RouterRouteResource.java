/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for router's route.
 */
public class RouterRouteResource {

    private final UUID routerId;

    /**
     * Constructor your own route
     *
     * @param UUID
     *            routerId ID of a router.
     */
    public RouterRouteResource(UUID routerId) {
        this.routerId = routerId;
    }

    /**
     * Handler for creating a router route.
     *
     * @param router
     *            Route object.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_ROUTE_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Route route, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.WRITE, routerId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to add route to this router.");
        }

        RouteDao dao = daoFactory.getRouteDao();
        route.setRouterId(routerId);
        UUID id = dao.create(route);
        return Response.created(
                ResourceUriBuilder.getRoute(uriInfo.getBaseUri(), id)).build();
    }

    /**
     * Handler to list routes.
     *
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @return A list of Route objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_ROUTE_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Route> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.READ, routerId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view these routes.");
        }

        RouteDao dao = daoFactory.getRouteDao();
        List<Route> routes = dao.list(routerId);
        if (routes != null) {
            for (UriResource resource : routes) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return routes;
    }
}