/*
 * @(#)AdRouteResource        1.6 11/09/05
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
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.AdRouteDataAccessor;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for advertising routes.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
@Path("/ad_routes")
public class AdRouteResource extends RestResource {
    /*
     * Implements REST API end points for ad_routes.
     */

    private final static Logger log = LoggerFactory
            .getLogger(AdRouteResource.class);

    /**
     * Get the advertising route with the given ID.
     * 
     * @param id
     *            AdRoute UUID.
     * @return AdRoute object.
     * @throws StateAccessException
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public AdRoute get(@PathParam("id") UUID id) throws StateAccessException {
        // Get a advertising route for the given ID.
        AdRouteDataAccessor dao = new AdRouteDataAccessor(zookeeperConn,
                zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
        AdRoute adRoute = null;
        try {
            adRoute = dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        return adRoute;
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") UUID id, AdRoute adRoute)
            throws StateAccessException {
        AdRouteDataAccessor dao = new AdRouteDataAccessor(zookeeperConn,
                zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
        try {
            dao.update(id, adRoute);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        return Response.ok().build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) throws StateAccessException {
        AdRouteDataAccessor dao = new AdRouteDataAccessor(zookeeperConn,
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
     * Sub-resource class for bgp's advertising route.
     */
    public static class BgpAdRouteResource extends RestResource {

        private UUID bgpId = null;

        /**
         * Constructor.
         * 
         * @param zkConn
         *            ZooKeeper connection string.
         * @param bgpId
         *            UUID of a bgp.
         */
        public BgpAdRouteResource(String zkConn, UUID bgpId) {
            this.zookeeperConn = zkConn;
            this.bgpId = bgpId;
        }

        /**
         * Index of advertising routes belonging to the bgp.
         * 
         * @return A list of advertising routes.
         * @throws StateAccessException
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public AdRoute[] list() throws StateAccessException {
            AdRouteDataAccessor dao = new AdRouteDataAccessor(zookeeperConn,
                    zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
            AdRoute[] adRoutes = null;
            try {
                adRoutes = dao.list(bgpId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
            return adRoutes;
        }

        /**
         * Handler for create advertising route.
         * 
         * @param adRoute
         *            AdRoute object mapped to the request input.
         * @throws StateAccessException
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(AdRoute adRoute, @Context UriInfo uriInfo)
                throws StateAccessException {
            adRoute.setBgpId(bgpId);
            AdRouteDataAccessor dao = new AdRouteDataAccessor(zookeeperConn,
                    zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
            UUID id = null;
            try {
                id = dao.create(adRoute);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            URI uri = uriInfo.getBaseUriBuilder().path("ad_routes/" + id)
                    .build();
            return Response.created(uri).build();
        }
    }

}
