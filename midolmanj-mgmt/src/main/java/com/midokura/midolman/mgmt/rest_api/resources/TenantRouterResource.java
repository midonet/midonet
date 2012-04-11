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
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for tenant's virtual router.
 */
public class TenantRouterResource {

    private String tenantId = null;

    /**
     * Constructor
     *
     * @param tenantId
     *            ID of a tenant.
     */
    public TenantRouterResource(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * Handler for creating a tenant router.
     *
     * @param router
     *            Router object.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_ROUTER_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Router router, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.tenantAuthorized(context, AuthAction.READ, tenantId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to add router to this tenant.");
        }

        RouterDao dao = daoFactory.getRouterDao();
        router.setTenantId(tenantId);
        UUID id = dao.create(router);
        return Response.created(
                ResourceUriBuilder.getRouter(uriInfo.getBaseUri(), id)).build();
    }

    /**
     * Handler to list tenant routers.
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
     * @return A list of Router objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Router> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.tenantAuthorized(context, AuthAction.READ, tenantId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view routers of this tenant.");
        }

        RouterDao dao = daoFactory.getRouterDao();
        List<Router> routers = dao.list(tenantId);
        if (routers != null) {
            for (UriResource resource : routers) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return routers;
    }
}