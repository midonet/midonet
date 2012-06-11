/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
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

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.rest_api.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.rest_api.resources.PortResource.BridgePeerPortResource;
import com.midokura.midolman.mgmt.rest_api.resources.PortResource.BridgePortResource;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for Virtual bridges.
 */
public class BridgeResource {
    /*
     * Implements REST API end points for bridges.
     */

    private final static Logger log = LoggerFactory
            .getLogger(BridgeResource.class);

    /**
     * Handler to deleting a bridge.
     *
     * @param id
     *            Bridge ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this bridge.");
        }

        BridgeDao dao = daoFactory.getBridgeDao();
        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }
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
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @return A Bridge object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public Bridge get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge.");
        }

        BridgeDao dao = daoFactory.getBridgeDao();
        Bridge bridge = dao.get(id);
        if (bridge == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        bridge.setBaseUri(uriInfo.getBaseUri());

        return bridge;
    }

    /**
     * Port resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgePortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORTS)
    public BridgePortResource getPortResource(@PathParam("id") UUID id) {
        return new BridgePortResource(id);
    }

    /**
     * Filtering database resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgeFilterDbResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.FILTER_DB)
    public BridgeFilterDbResource getBridgeFilterDbResource(
            @PathParam("id") UUID id) {
        return new BridgeFilterDbResource(id);
    }

    /**
     * DHCP resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgeDhcpResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.DHCP)
    public BridgeDhcpResource getBridgeDhcpResource(@PathParam("id") UUID id) {
        return new BridgeDhcpResource(id);
    }

    /**
     * Peer port resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgePeerPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PEER_PORTS)
    public BridgePeerPortResource BridgePeerPortResource(
            @PathParam("id") UUID id) {
        return new BridgePeerPortResource(id);
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
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Bridge bridge,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this bridge.");
        }
        BridgeDao dao = daoFactory.getBridgeDao();
        bridge.setId(id);
        dao.update(bridge);
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
         * @param authorizer
         *            Authorizer object.
         * @throws StateAccessException
         *             Data access error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_BRIDGE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Bridge bridge, @Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer.tenantAuthorized(context, AuthAction.WRITE,
                    tenantId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add bridge to this tenant.");
            }

            BridgeDao dao = daoFactory.getBridgeDao();
            bridge.setTenantId(tenantId);
            UUID id = dao.create(bridge);
            return Response.created(
                    ResourceUriBuilder.getBridge(uriInfo.getBaseUri(), id))
                    .build();
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
         * @param authorizer
         *            Authorizer object.
         * @throws StateAccessException
         *             Data access error.
         * @return A list of Bridge objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Bridge> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer
                    .tenantAuthorized(context, AuthAction.READ, tenantId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view bridges of this tenant.");
            }

            BridgeDao dao = daoFactory.getBridgeDao();
            List<Bridge> bridges = dao.list(tenantId);
            if (bridges != null) {
                for (UriResource resource : bridges) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return bridges;
        }
    }
}
