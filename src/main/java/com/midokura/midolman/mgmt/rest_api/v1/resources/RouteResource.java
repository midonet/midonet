/*
 * @(#)RouteResource        1.6 11/09/10
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
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
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Root resource class for ports.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/routes")
public class RouteResource {
    /*
     * Implements REST API endpoints for routes.
     */

    private final static Logger log = LoggerFactory
            .getLogger(RouteResource.class);

    /**
     * Get the route with the given ID.
     * 
     * @param id
     *            Route UUID.
     * @return Route object.
     * @throws StateAccessException
     * @throws UnauthorizedException
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Route get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        // Get a route for the given ID.
        RouteDao dao = daoFactory.getRouteDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only see your own route.");
        }

        try {
            return dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        RouteDao dao = daoFactory.getRouteDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only delete your own route.");
        }

        try {
            dao.delete(id);
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
         * Constructor.
         * 
         * @param zkConn
         *            Zookeeper connection string.
         * @param routerId
         *            UUID of a router.
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
         * Return a list of routes.
         * 
         * @return A list of Route objects.
         * @throws StateAccessException
         * @throws UnauthorizedException
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Route> list(@Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {
            if (!isRouterOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own route.");
            }

            RouteDao dao = daoFactory.getRouteDao();
            try {
                return dao.list(routerId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
        }

        /**
         * Handler for create route API call.
         * 
         * @param port
         *            Router object mapped to the request input.
         * @throws StateAccessException
         * @throws UnauthorizedException
         * @throws Exception
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
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

            URI uri = uriInfo.getBaseUriBuilder().path("routes/" + id).build();
            return Response.created(uri).build();
        }
    }

    /**
     * Sub-resource class for port's route.
     */
    public static class PortRouteResource {

        private UUID portId = null;

        /**
         * Constructor.
         * 
         * @param zkConn
         *            Zookeeper connection string.
         * @param routerId
         *            UUID of a router.
         */
        public PortRouteResource(UUID portId) {
            this.portId = portId;
        }

        private boolean isPortOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException,
                ZkStateSerializationException {
            OwnerQueryable q = daoFactory.getPortDao();
            return AuthManager.isOwner(context, q, portId);
        }

        /**
         * Return a list of routes.
         * 
         * @return A list of Route objects.
         * @throws StateAccessException
         * @throws UnauthorizedException
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Route> list(@Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {

            if (!isPortOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own routes.");
            }

            RouteDao dao = daoFactory.getRouteDao();
            try {
                return dao.listByPort(portId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
        }

    }
}
