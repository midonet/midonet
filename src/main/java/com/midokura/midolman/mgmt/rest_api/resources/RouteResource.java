/*
 * @(#)RouteResource        1.6 11/09/10
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

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

import com.midokura.midolman.mgmt.auth.AuthManager;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Root resource class for ports.
 *
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouteResource {
    /*
     * Implements REST API endpoints for routes.
     */

    private final static Logger log = LoggerFactory
            .getLogger(RouteResource.class);

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
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @return A Route object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTE_JSON,
            MediaType.APPLICATION_JSON })
    public Route get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory) throws StateAccessException,
            UnauthorizedException {
        RouteDao dao = daoFactory.getRouteDao();
        if (!AuthManager.isOwner(context, (OwnerQueryable) dao, id)) {
            throw new UnauthorizedException("Can only see your own route.");
        }

        Route route = null;
        try {
            route = dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        route.setBaseUri(uriInfo.getBaseUri());
        return route;
    }

    /**
     * Handler to deleting a route.
     *
     * @param id
     *            Route ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     */
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        RouteDao dao = daoFactory.getRouteDao();
        if (!AuthManager.isOwner(context, (OwnerQueryable) dao, id)) {
            throw new UnauthorizedException("Can only delete your own route.");
        }

        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
    }

    /**
     * Sub-resource class for router's route.
     */
    public static class RouterRouteResource {

        private UUID routerId = null;

        /**
         * Constructor
         *
         * @param UUID
         *            routerId ID of a router.
         */
        public RouterRouteResource(UUID routerId) {
            this.routerId = routerId;
        }

        private boolean isRouterOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException,
                ZkStateSerializationException {
            OwnerQueryable q = daoFactory.getRouterDao();
            return AuthManager.isOwner(context, q, routerId);
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
         * @throws StateAccessException
         *             Data access error.
         * @throws UnauthorizedException
         *             Authentication/authorization error.
         * @return A list of Route objects.
         */
        @GET
        @Produces({ VendorMediaType.APPLICATION_ROUTE_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Route> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
            if (!isRouterOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own route.");
            }

            RouteDao dao = daoFactory.getRouteDao();
            List<Route> routes = null;
            try {
                routes = dao.list(routerId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            for (UriResource resource : routes) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
            return routes;
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
         * @throws StateAccessException
         *             Data access error.
         * @throws UnauthorizedException
         *             Authentication/authorization error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes({ VendorMediaType.APPLICATION_ROUTE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Route route, @Context UriInfo uriInfo,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, ZkStateSerializationException,
                UnauthorizedException {
            if (!isRouterOwner(context, daoFactory)) {
                throw new UnauthorizedException(
                        "Can only create your own routes.");
            }

            RouteDao dao = daoFactory.getRouteDao();
            route.setRouterId(routerId);

            UUID id = null;
            try {
                id = dao.create(route);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            return Response.created(
                    UriManager.getRoute(uriInfo.getBaseUri(), id)).build();
        }
    }
}
