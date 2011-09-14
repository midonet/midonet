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
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.AdRouteDataAccessor;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.mgmt.rest_api.v1.resources.AdRouteResource;

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

    private final static Logger log =
        LoggerFactory.getLogger(AdRouteResource.class);

    /**
     * Get the advertising route with the given ID.
     * 
     * @param id
     *            AdRoute UUID.
     * @return AdRoute object.
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public AdRoute get(@PathParam("id") UUID id) {
        // Get a advertising route for the given ID.
        AdRouteDataAccessor dao = new AdRouteDataAccessor(zookeeperConn);
        AdRoute adRoute = null;
        try {
            adRoute = dao.get(id);
        } catch (Exception ex) {
            log.error("Error getting adRoute", ex);
            throw new WebApplicationException(Response.status(
                    Response.Status.INTERNAL_SERVER_ERROR).type(
                    MediaType.APPLICATION_JSON).build());
        }
        return adRoute;
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") UUID id, AdRoute adRoute) {
        AdRouteDataAccessor dao = new AdRouteDataAccessor(zookeeperConn);
        try {
            dao.update(id, adRoute);
        } catch (Exception ex) {
            log.error("Error updating bridge", ex);
            throw new WebApplicationException(Response.status(
                    Response.Status.INTERNAL_SERVER_ERROR).type(
                    MediaType.APPLICATION_JSON).build());
        }
        return Response.ok().build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        AdRouteDataAccessor dao = new AdRouteDataAccessor(zookeeperConn);
        try {
            dao.delete(id);
        } catch (Exception ex) {
            log.error("Error deleting adRoute", ex);
            throw new WebApplicationException(ex, Response.status(
                    Response.Status.INTERNAL_SERVER_ERROR).type(
                    MediaType.APPLICATION_JSON).build());
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
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public AdRoute[] list() {
            AdRouteDataAccessor dao = new AdRouteDataAccessor(zookeeperConn);
            AdRoute[] adRoutes = null;
            try {
                adRoutes = dao.list(bgpId);
            } catch (Exception ex) {
                log.error("Error getting advertising routes", ex);
                throw new WebApplicationException(Response.status(
                        Response.Status.INTERNAL_SERVER_ERROR).type(
                        MediaType.APPLICATION_JSON).build());
            }
            return adRoutes;
        }

        /**
         * Handler for create advertising route.
         * 
         * @param adRoute
         *            AdRoute object mapped to the request input.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(AdRoute adRoute, @Context UriInfo uriInfo) {
            adRoute.setBgpId(bgpId);
            AdRouteDataAccessor dao = new AdRouteDataAccessor(zookeeperConn);
            UUID id = null;
            try {
                id = dao.create(adRoute);
            } catch (Exception ex) {
                log.error("Error creating advertising route", ex);
                throw new WebApplicationException(Response.status(
                        Response.Status.INTERNAL_SERVER_ERROR).type(
                        MediaType.APPLICATION_JSON).build());
            }

            URI uri = uriInfo.getBaseUriBuilder()
                .path("ad_routes/" + id).build();
            return Response.created(uri).build();
        }
    }

}
