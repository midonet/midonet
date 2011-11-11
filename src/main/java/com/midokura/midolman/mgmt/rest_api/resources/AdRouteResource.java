/*
 * @(#)AdRouteResource        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

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
import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for advertising routes.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
@Path("/ad_routes")
public class AdRouteResource {
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
     * @throws UnauthorizedException
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public AdRoute get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        // Get a advertising route for the given ID.
        AdRouteDao dao = daoFactory.getAdRouteDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException(
                    "Can only see your own advertised route.");
        }

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

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        AdRouteDao dao = daoFactory.getAdRouteDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException(
                    "Can only delete your own advertised route.");
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
     * Sub-resource class for bgp's advertising route.
     */
    public static class BgpAdRouteResource {

        private UUID bgpId = null;

        /**
         * Constructor.
         * 
         * @param zkConn
         *            ZooKeeper connection string.
         * @param bgpId
         *            UUID of a bgp.
         */
        public BgpAdRouteResource(UUID bgpId) {
            this.bgpId = bgpId;
        }

        private boolean isBgpOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException {
            OwnerQueryable q = daoFactory.getBgpDao();
            return AuthManager.isOwner(context, q, bgpId);
        }

        /**
         * Index of advertising routes belonging to the bgp.
         * 
         * @return A list of advertising routes.
         * @throws StateAccessException
         * @throws UnauthorizedException
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<AdRoute> list(@Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {

            if (!isBgpOwner(context, daoFactory)) {
                throw new UnauthorizedException(
                        "Can only see your own advertised route.");
            }

            AdRouteDao dao = daoFactory.getAdRouteDao();
            List<AdRoute> adRoutes = null;
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
         * @throws UnauthorizedException
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(AdRoute adRoute, @Context UriInfo uriInfo,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {

            if (!isBgpOwner(context, daoFactory)) {
                throw new UnauthorizedException(
                        "Can only create for your own BGP.");
            }

            AdRouteDao dao = daoFactory.getAdRouteDao();
            adRoute.setBgpId(bgpId);

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
