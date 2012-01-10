/*
 * @(#)BgpResource        1.6 11/09/05
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
import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.mgmt.rest_api.resources.AdRouteResource.BgpAdRouteResource;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for bgps.
 *
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
public class BgpResource {
    /*
     * Implements REST API end points for bgps.
     */

    private final static Logger log = LoggerFactory
            .getLogger(BgpResource.class);

    /**
     * Advertising route resource locator for chains.
     *
     * @param id
     *            BGP ID from the request.
     * @returns BgpAdRouteResource object to handle sub-resource requests.
     */
    @Path("/{id}" + UriManager.AD_ROUTES)
    public BgpAdRouteResource getBgpAdRouteResource(@PathParam("id") UUID id) {
        return new BgpAdRouteResource(id);
    }

    /**
     * Handler to getting BGP.
     *
     * @param id
     *            BGP ID from the request.
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
     * @return A BGP object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_BGP_JSON,
            MediaType.APPLICATION_JSON })
    public Bgp get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {

        // Get a bgp for the given ID.
        BgpDao dao = daoFactory.getBgpDao();
        if (!AuthManager.isOwner(context, (OwnerQueryable) dao, id)) {
            throw new UnauthorizedException("Can only see your own BGP.");
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
        bgp.setBaseUri(uriInfo.getBaseUri());
        return bgp;
    }

    /**
     * Handler to deleting BGP.
     *
     * @param id
     *            BGP ID from the request.
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
        BgpDao dao = daoFactory.getBgpDao();
        if (!AuthManager.isOwner(context, (OwnerQueryable) dao, id)) {
            throw new UnauthorizedException("Can only see your own BGP.");
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
     * Sub-resource class for port's BGP.
     */
    public static class PortBgpResource {

        private UUID portId = null;

        /**
         * Constructor
         *
         * @param portId
         *            ID of a port.
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
         * Handler to getting a list of BGPs.
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
         * @return A list of BGP objects.
         */
        @GET
        @Produces({ VendorMediaType.APPLICATION_BGP_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Bgp> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
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
            for (UriResource resource : bgps) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
            return bgps;
        }

        /**
         * Handler for creating BGP.
         *
         * @param chain
         *            BGP object.
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
        @Consumes({ VendorMediaType.APPLICATION_BGP_JSON,
                MediaType.APPLICATION_JSON })
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

            return Response
                    .created(UriManager.getBgp(uriInfo.getBaseUri(), id))
                    .build();
        }
    }

}
