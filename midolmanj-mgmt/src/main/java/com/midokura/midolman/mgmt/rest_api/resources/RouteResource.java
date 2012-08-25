/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.authorizer.Authorizer;
import com.midokura.midolman.mgmt.auth.authorizer.RouteAuthorizer;
import com.midokura.midolman.mgmt.auth.authorizer.RouterAuthorizer;
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.Route.RouteGroupSequence;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.http.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.BadRequestHttpException;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Root resource class for ports.
 */
@RequestScoped
public class RouteResource {
    /*
     * Implements REST API endpoints for routes.
     */

    private final static Logger log = LoggerFactory
            .getLogger(RouteResource.class);

    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final Authorizer authorizer;
    private final RouteDao dao;

    @Inject
    public RouteResource(UriInfo uriInfo, SecurityContext context,
                         RouteAuthorizer authorizer, RouteDao dao) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.authorizer = authorizer;
        this.dao = dao;
    }

    /**
     * Handler to deleting a route.
     *
     * @param id
     *            Route ID from the request.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, InvalidStateOperationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this route.");
        }

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
     * @throws StateAccessException
     *             Data access error.
     * @return A Route object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTE_JSON,
            MediaType.APPLICATION_JSON })
    public Route get(@PathParam("id") UUID id)
            throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this route.");
        }

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
    @RequestScoped
    public static class RouterRouteResource {

        private final UUID routerId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final Validator validator;
        private final RouteDao dao;

        @Inject
        public RouterRouteResource(UriInfo uriInfo,
                                   SecurityContext context,
                                   RouterAuthorizer authorizer,
                                   Validator validator,
                                   RouteDao dao,
                                   @Assisted UUID routerId) {
            this.routerId = routerId;
            this.context = context;
            this.uriInfo = uriInfo;
            this.authorizer = authorizer;
            this.validator = validator;
            this.dao = dao;
        }

        /**
         * Handler for creating a router route.
         *
         * @param route
         *            Route object.
         * @throws StateAccessException
         *             Data access error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_ROUTE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Route route)
                throws StateAccessException, InvalidStateOperationException {

            route.setRouterId(routerId);

            Set<ConstraintViolation<Route>> violations = validator.validate(
                    route, RouteGroupSequence.class);
            if (!violations.isEmpty()) {
                throw new BadRequestHttpException(violations);
            }

            if (!authorizer.authorize(context, AuthAction.WRITE, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add route to this router.");
            }

            UUID id = dao.create(route);
            return Response.created(
                    ResourceUriBuilder.getRoute(uriInfo.getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to list routes.
         *
         * @throws StateAccessException
         *             Data access error.
         * @return A list of Route objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_ROUTE_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Route> list()
                throws StateAccessException {

            if (!authorizer.authorize(context, AuthAction.READ, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these routes.");
            }

            List<Route> routes = dao.findByRouter(routerId);
            if (routes != null) {
                for (UriResource resource : routes) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return routes;
        }
    }
}
