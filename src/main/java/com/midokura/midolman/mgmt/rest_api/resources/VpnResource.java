/*
 * @(#)VpnResource        1.6 11/10/25
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
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for vpns.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
public class VpnResource {
    /*
     * Implements REST API end points for vpns.
     */

    private final static Logger log = LoggerFactory
            .getLogger(VpnResource.class);

    /**
     * Handler to getting VPN configuration record.
     * 
     * @param id
     *            VPN ID from the request.
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
     * @return A Vpn object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_VPN_JSON,
            MediaType.APPLICATION_JSON })
    public Vpn get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {

        // Get a vpn for the given ID.
        VpnDao dao = daoFactory.getVpnDao();

        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only see your own VPN.");
        }

        Vpn vpn = null;
        try {
            vpn = dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        vpn.setBaseUri(uriInfo.getBaseUri());
        return vpn;
    }

    /**
     * Handler to deleting a VPN record.
     * 
     * @param id
     *            VPN ID from the request.
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
        VpnDao dao = daoFactory.getVpnDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException(
                    "Can only see your own advertised route.");
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
     * Sub-resource class for port's VPN.
     */
    public static class PortVpnResource {

        private UUID portId = null;

        /**
         * Constructor
         * 
         * @param portId
         *            ID of a port.
         */
        public PortVpnResource(UUID portId) {
            this.portId = portId;
        }

        private boolean isPortOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException {
            PortDao q = daoFactory.getPortDao();
            return AuthManager.isOwner(context, q, portId);
        }

        /**
         * Handler to getting a list of VPN records.
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
         * @return A list of VPN objects.
         */
        @GET
        @Produces({ VendorMediaType.APPLICATION_VPN_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Vpn> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
            if (!isPortOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own VPN.");
            }

            VpnDao dao = daoFactory.getVpnDao();
            List<Vpn> vpns = null;
            try {
                vpns = dao.list(portId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
            for (UriResource resource : vpns) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
            return vpns;
        }

        /**
         * Handler for creating a VPN record.
         * 
         * @param chain
         *            VPN object.
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
        @Consumes({ VendorMediaType.APPLICATION_VPN_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Vpn vpn, @Context UriInfo uriInfo,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
            if (!isPortOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only create your own VPN.");
            }
            VpnDao dao = daoFactory.getVpnDao();
            vpn.setPortId(portId);

            UUID id = null;
            try {
                id = dao.create(vpn);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            return Response
                    .created(UriManager.getVpn(uriInfo.getBaseUri(), id))
                    .build();
        }
    }
}
