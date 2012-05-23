/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;

import javax.annotation.security.RolesAllowed;
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

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.rest_api.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.rest_api.resources.BridgeResource.TenantBridgeResource;
import com.midokura.midolman.mgmt.rest_api.resources.ChainResource.TenantChainResource;
import com.midokura.midolman.mgmt.rest_api.resources.PortGroupResource.TenantPortGroupResource;
import com.midokura.midolman.mgmt.rest_api.resources.RouterResource.TenantRouterResource;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for tenants.
 */
public class TenantResource {
    /*
     * Implements REST API endpoints for tenants.
     */

    private final static Logger log = LoggerFactory
            .getLogger(TenantResource.class);

    /**
     * Handler for creating a tenant.
     *
     * @param tenant
     *            Tenant object.
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
    @Consumes({ VendorMediaType.APPLICATION_TENANT_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Tenant tenant, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException("Not authorized to create tenant.");
        }

        TenantDao dao = daoFactory.getTenantDao();
        String id = dao.create(tenant);
        return Response.created(
                ResourceUriBuilder.getTenant(uriInfo.getBaseUri(), id)).build();
    }

    /**
     * Handler for deleting a tenant.
     *
     * @param id
     *            Tenant ID from the request.
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
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") String id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException("Not authorized to delete tenant.");
        }

        TenantDao dao = daoFactory.getTenantDao();
        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }
    }

    /**
     * Bridge resource locator for tenants
     *
     * @param id
     *            Tenant ID from the request.
     * @returns TenantBridgeResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.BRIDGES)
    public TenantBridgeResource getBridgeResource(@PathParam("id") String id) {
        return new TenantBridgeResource(id);
    }

    /**
     * Chain resource locator for tenants
     *
     * @param id
     *            Tenant ID from the request.
     * @returns TenantChainResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.CHAINS)
    public TenantChainResource getChainResource(@PathParam("id") String id) {
        return new TenantChainResource(id);
    }

    /**
     * Port group resource locator for tenants
     *
     * @param id
     *            Tenant ID from the request.
     * @returns TenantPortGroupResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORT_GROUPS)
    public TenantPortGroupResource getPortGroupResource(
            @PathParam("id") String id) {
        return new TenantPortGroupResource(id);
    }

    /**
     * Router resource locator for tenants.
     *
     * @param id
     *            Tenant ID from the request.
     * @returns TenantRouterResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.ROUTERS)
    public TenantRouterResource getRouterResource(@PathParam("id") String id) {
        return new TenantRouterResource(id);
    }

    /**
     * Handler for listing all the tenants.
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
     * @returns A list of Tenant objects.
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Tenant> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException("Not authorized to view tenants.");
        }

        TenantDao dao = daoFactory.getTenantDao();
        List<Tenant> tenants = dao.list();
        if (tenants != null) {
            for (UriResource resource : tenants) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return tenants;
    }

    /**
     * Handler to getting a tenant.
     *
     * @param id
     *            Tenant ID from the request.
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
     * @return A Tenant object.
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_TENANT_JSON,
            MediaType.APPLICATION_JSON })
    public Tenant get(@PathParam("id") String id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.tenantAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException("Not authorized to view tenants.");
        }

        TenantDao dao = daoFactory.getTenantDao();
        Tenant tenant = dao.get(id);
        if (tenant == null) {
            throw new NotFoundHttpException(
                    "The requested tenant was not found.");
        }
        tenant.setBaseUri(uriInfo.getBaseUri());
        return tenant;
    }
}
