/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.BadRequestHttpException;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.rest_api.jaxrs.NotFoundHttpException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for ports.
 */
public class RouteResource {
    /*
     * Implements REST API endpoints for routes.
     */

    private final static Logger log = LoggerFactory
            .getLogger(RouteResource.class);

    /**
     * Handler to deleting a route.
     *
     * @param id
     *            Route ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routeAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this route.");
        }

        RouteDao dao = daoFactory.getRouteDao();
        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }
    }

    /**
     * Handler to getting a route.
     *
     * @param id
     *            Route ID from the request.
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
     * @return A Route object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTE_JSON,
            MediaType.APPLICATION_JSON })
    public Route get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.routeAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this route.");
        }

        RouteDao dao = daoFactory.getRouteDao();
        Route route = dao.get(id);
        if (route == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        route.setBaseUri(uriInfo.getBaseUri());

        return route;
    }

    /**
     * Sub-resource class for router's route.
     */
    public static class RouterRouteResource {

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
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_ROUTE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Route route, @Context UriInfo uriInfo,
                @Context SecurityContext context,
                @Context DaoFactory daoFactory, @Context Authorizer authorizer,
                @Context Validator validator) throws StateAccessException {

            Set<ConstraintViolation<Route>> violations = validator
                    .validate(route);
            if (!violations.isEmpty()) {
                throw new BadRequestHttpException(violations);
            }

            if (!authorizer.routerAuthorized(context, AuthAction.WRITE,
                    routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add route to this router.");
            }

            RouteDao dao = daoFactory.getRouteDao();
            route.setRouterId(routerId);
            UUID id = dao.create(route);
            return Response.created(
                    ResourceUriBuilder.getRoute(uriInfo.getBaseUri(), id))
                    .build();
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

            if (!authorizer
                    .routerAuthorized(context, AuthAction.READ, routerId)) {
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
}
