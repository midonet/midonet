/*
 * @(#)BridgeResource        1.6 11/09/05
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
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.resources.PortResource.BridgePortResource;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for Virtual bridges.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class BridgeResource {
    /*
     * Implements REST API end points for bridges.
     */

    private final static Logger log = LoggerFactory
            .getLogger(BridgeResource.class);

    /**
     * Port resource locator for bridges.
     * 
     * @param id
     *            Bridge ID from the request.
     * @returns BridgePortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + UriManager.PORTS)
    public BridgePortResource getPortResource(@PathParam("id") UUID id) {
        return new BridgePortResource(id);
    }

    /**
     * Handler to getting a bridge.
     * 
     * @param id
     *            Bridge ID from the request.
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
     * @return A Bridge object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public Bridge get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory) throws StateAccessException,
            UnauthorizedException {
        BridgeDao dao = daoFactory.getBridgeDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only see your own bridge.");
        }

        Bridge bridge = null;
        try {
            bridge = dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }

        bridge.setBaseUri(uriInfo.getBaseUri());
        return bridge;
    }

    /**
     * Handler to updating a bridge.
     * 
     * @param id
     *            Bridge ID from the request.
     * @param bridge
     *            Bridge object.
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
     */
    @PUT
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
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

    /**
     * Handler to deleting a bridge.
     * 
     * @param id
     *            Bridge ID from the request.
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
        BridgeDao dao = daoFactory.getBridgeDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only update your own bridge.");
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
     * Sub-resource class for tenant's virtual switch.
     */
    public static class TenantBridgeResource {

        private String tenantId = null;

        /**
         * Constructor.
         * 
         * @param tenantId
         *            UUID of a tenant.
         */
        public TenantBridgeResource(String tenantId) {
            this.tenantId = tenantId;
        }

        /**
         * Handler to list tenant bridges.
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
         * @return A list of Bridge objects.
         */
        @GET
        @Produces({ VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Bridge> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
            if (!AuthManager.isSelf(context, tenantId)) {
                throw new UnauthorizedException(
                        "Can only see your own bridges.");
            }

            BridgeDao dao = daoFactory.getBridgeDao();
            List<Bridge> bridges = null;
            try {
                bridges = dao.list(tenantId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            for (UriResource resource : bridges) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
            return bridges;
        }

        /**
         * Handler for creating a tenant bridge.
         * 
         * @param bridge
         *            Bridge object.
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
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes({ VendorMediaType.APPLICATION_BRIDGE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Bridge bridge, @Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
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

            bridge.setId(id);
            return Response.created(
                    UriManager.getBridge(uriInfo.getBaseUri(), bridge)).build();
        }
    }
}
