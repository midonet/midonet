/*
 * @(#)BgpResource        1.6 11/09/05
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
import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.rest_api.v1.resources.AdRouteResource.BgpAdRouteResource;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for bgps.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
@Path("/bgps")
public class BgpResource {
    /*
     * Implements REST API end points for bgps.
     */

    private final static Logger log = LoggerFactory
            .getLogger(BgpResource.class);

    /**
     * Advertising route resource locator for bgps
     */
    @Path("/{id}/ad_routes")
    public BgpAdRouteResource getBgpAdRouteResource(@PathParam("id") UUID id) {
        return new BgpAdRouteResource(id);
    }

    /**
     * Get the BGP with the given ID.
     * 
     * @param id
     *            BGP UUID.
     * @return Bgp object.
     * @throws StateAccessException
     * @throws UnauthorizedException
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Bgp get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context DaoFactory daoFactory) throws StateAccessException,
            UnauthorizedException {

        // Get a bgp for the given ID.
        BgpDao dao = daoFactory.getBgpDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException(
                    "Can only see your own advertised route.");
        }

        Bgp bgp = null;
        try {
            bgp = dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        return bgp;
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {

        BgpDao dao = daoFactory.getBgpDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException(
                    "Can only see your own advertised route.");
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
     * Sub-resource class for port's BGP.
     */
    public static class PortBgpResource {

        private UUID portId = null;

        /**
         * Constructor.
         * 
         * @param zkConn
         *            ZooKeeper connection string.
         * @param portId
         *            UUID of a port.
         */
        public PortBgpResource(UUID portId) {
            this.portId = portId;
        }

        private boolean isPortOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException {
            OwnerQueryable q = daoFactory.getPortDao();
            return AuthManager.isOwner(context, q, portId);
        }

        /**
         * Index of bgps belonging to the port.
         * 
         * @return A list of bgps.
         * @throws StateAccessException
         * @throws UnauthorizedException
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Bgp> list(@Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {
            if (!isPortOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own BGP.");
            }

            BgpDao dao = daoFactory.getBgpDao();
            List<Bgp> bgps = null;
            try {
                bgps = dao.list(portId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
            return bgps;
        }

        /**
         * Handler for create bgp.
         * 
         * @param bgp
         *            Bgp object mapped to the request input.
         * @throws StateAccessException
         * @throws UnauthorizedException
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Bgp bgp, @Context UriInfo uriInfo,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
            if (!isPortOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only create your own BGP.");
            }
            BgpDao dao = daoFactory.getBgpDao();
            bgp.setPortId(portId);

            UUID id = null;
            try {
                id = dao.create(bgp);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            URI uri = uriInfo.getBaseUriBuilder().path("bgps/" + id).build();
            return Response.created(uri).build();
        }
    }

}
