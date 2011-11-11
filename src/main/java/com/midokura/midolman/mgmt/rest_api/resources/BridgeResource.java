/*
 * @(#)BridgeResource        1.6 11/09/05
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
import javax.ws.rs.PUT;
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
import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.rest_api.resources.PortResource.BridgePortResource;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for Virtual bridges.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/bridges")
public class BridgeResource {
    /*
     * Implements REST API end points for bridges.
     */

    private final static Logger log = LoggerFactory
            .getLogger(BridgeResource.class);

    /**
     * Port resource locator for bridges
     */
    @Path("/{id}/ports")
    public BridgePortResource getPortResource(@PathParam("id") UUID id) {
        return new BridgePortResource(id);
    }

    /**
     * Get the bridge with the given ID.
     * 
     * @param id
     *            Bridge UUID.
     * @return Bridge object.
     * @throws StateAccessException
     * @throws UnauthorizedException
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Bridge get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        // Get a bridge for the given ID.
        BridgeDao dao = daoFactory.getBridgeDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only see your own bridge.");
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

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") UUID id, Bridge bridge,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        BridgeDao dao = daoFactory.getBridgeDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only update your own bridge.");
        }

        bridge.setId(id);
        try {
            dao.update(bridge);
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
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        BridgeDao dao = daoFactory.getBridgeDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only update your own bridge.");
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
     * Sub-resource class for tenant's virtual switch.
     */
    public static class TenantBridgeResource {

        private String tenantId = null;

        /**
         * Constructor.
         * 
         * @param zkConn
         *            ZooKeeper connection string.
         * @param tenantId
         *            UUID of a tenant.
         */
        public TenantBridgeResource(String tenantId) {
            this.tenantId = tenantId;
        }

        /**
         * Index of bridges belonging to the tenant.
         * 
         * @return A list of bridges.
         * @throws StateAccessException
         * @throws UnauthorizedException
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Bridge> list(@Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {

            if (!AuthManager.isSelf(context, tenantId)) {
                throw new UnauthorizedException(
                        "Can only see your own bridges.");
            }

            BridgeDao dao = daoFactory.getBridgeDao();
            try {
                return dao.list(tenantId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
        }

        /**
         * Handler for create bridge.
         * 
         * @param bridge
         *            Bridge object mapped to the request input.
         * @throws StateAccessException
         * @throws UnauthorizedException
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Bridge bridge, @Context UriInfo uriInfo,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {

            if (!AuthManager.isSelf(context, tenantId)) {
                throw new UnauthorizedException(
                        "Can only see your own bridges.");
            }

            BridgeDao dao = daoFactory.getBridgeDao();
            bridge.setTenantId(tenantId);

            UUID id = null;
            try {
                id = dao.create(bridge);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            URI uri = uriInfo.getBaseUriBuilder().path("bridges/" + id).build();
            return Response.created(uri).build();
        }
    }

}
