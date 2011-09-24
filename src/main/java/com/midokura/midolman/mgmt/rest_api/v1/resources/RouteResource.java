/*
 * @(#)RouteResource        1.6 11/09/10
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
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
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.RouteDataAccessor;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for ports.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/routes")
public class RouteResource extends RestResource {
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
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Route get(@PathParam("id") UUID id) throws StateAccessException {
        // Get a route for the given ID.
        RouteDataAccessor dao = new RouteDataAccessor(zookeeperConn,
                zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
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
    public void delete(@PathParam("id") UUID id) throws StateAccessException {
        RouteDataAccessor dao = new RouteDataAccessor(zookeeperConn,
                zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
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
    public static class RouterRouteResource extends RestResource {

        private UUID routerId = null;

        /**
         * Default constructor.
         * 
         * @param zkConn
         *            Zookeeper connection string.
         * @param routerId
         *            UUID of a router.
         */
        public RouterRouteResource(String zkConn, UUID routerId) {
            this.zookeeperConn = zkConn;
            this.routerId = routerId;
        }

        /**
         * Return a list of routes.
         * 
         * @return A list of Route objects.
         * @throws StateAccessException
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Route[] list() throws StateAccessException {
            RouteDataAccessor dao = new RouteDataAccessor(zookeeperConn,
                    zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
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
         * @throws Exception
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Route route, @Context UriInfo uriInfo)
                throws StateAccessException {
            route.setRouterId(routerId);
            RouteDataAccessor dao = new RouteDataAccessor(zookeeperConn,
                    zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);

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
}
