/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

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
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for tenant's virtual switch.
 */
public class TenantBridgeResource {

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
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Bridge bridge, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.tenantAuthorized(context, AuthAction.WRITE, tenantId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to add bridge to this tenant.");
        }

        BridgeDao dao = daoFactory.getBridgeDao();
        bridge.setTenantId(tenantId);
        UUID id = dao.create(bridge);
        return Response.created(
                ResourceUriBuilder.getBridge(uriInfo.getBaseUri(), id)).build();
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
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Bridge> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.tenantAuthorized(context, AuthAction.READ, tenantId)) {
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