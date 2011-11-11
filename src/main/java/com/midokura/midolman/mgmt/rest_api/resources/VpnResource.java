/*
 * @(#)VpnResource        1.6 11/10/25
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
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for vpns.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
@Path("/vpns")
public class VpnResource {
    /*
     * Implements REST API end points for vpns.
     */

    private final static Logger log = LoggerFactory
            .getLogger(VpnResource.class);

    /**
     * Get the VPN with the given ID.
     * 
     * @param id
     *            VPN UUID.
     * @return Vpn object.
     * @throws StateAccessException
     * @throws UnauthorizedException
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Vpn get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context DaoFactory daoFactory) throws StateAccessException,
            UnauthorizedException {

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
        return vpn;
    }

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
         * Constructor.
         * 
         * @param zkConn
         *            ZooKeeper connection string.
         * @param portId
         *            UUID of a port.
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
         * Index of vpns belonging to the port.
         * 
         * @return A list of vpns.
         * @throws StateAccessException
         * @throws UnauthorizedException
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Vpn> list(@Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {
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
            return vpns;
        }

        /**
         * Handler for create vpn.
         * 
         * @param vpn
         *            Vpn object mapped to the request input.
         * @throws StateAccessException
         * @throws UnauthorizedException
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
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

            URI uri = uriInfo.getBaseUriBuilder().path("vpns/" + id).build();
            return Response.created(uri).build();
        }
    }
}
