/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.http.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.resources.BridgeResource.TenantBridgeResource;
import com.midokura.midolman.mgmt.rest_api.resources.ChainResource.TenantChainResource;
import com.midokura.midolman.mgmt.rest_api.resources.PortGroupResource.TenantPortGroupResource;
import com.midokura.midolman.mgmt.rest_api.resources.RouterResource.TenantRouterResource;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.List;

/**
 * Root resource class for tenants.
 */
@RequestScoped
public class TenantResource {

    private final static Logger log = LoggerFactory
            .getLogger(TenantResource.class);

    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final Authorizer authorizer;
    private final TenantDao dao;
    private final ResourceFactory factory;

    @Inject
    public TenantResource(UriInfo uriInfo, SecurityContext context,
                          Authorizer authorizer, TenantDao dao,
                          ResourceFactory factory) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.authorizer = authorizer;
        this.dao = dao;
        this.factory = factory;
    }

    /**
     * Handler for creating a tenant.
     *
     * @param tenant
     *            Tenant object.
     * @throws StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_TENANT_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Tenant tenant)
            throws StateAccessException, InvalidStateOperationException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException(
                    "Not authorized to create tenant.");
        }

        String id = dao.create(tenant);
        return Response.created(
                ResourceUriBuilder.getTenant(uriInfo.getBaseUri(), id)).build();
    }

    /**
     * Handler for deleting a tenant.
     *
     * @param id
     *            Tenant ID from the request.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") String id)
            throws StateAccessException, InvalidStateOperationException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete tenant.");
        }

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
        return factory.getTenantBridgeResource(id);
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
        return factory.getTenantChainResource(id);
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
        return factory.getTenantPortGroupResource(id);
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
        return factory.getTenantRouterResource(id);
    }

    /**
     * Handler for listing all the tenants.
     *
     * @throws StateAccessException
     *             Data access error.
     * @returns A list of Tenant objects.
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Tenant> list() throws StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException("Not authorized to view tenants.");
        }

        List<Tenant> tenants = dao.findAll();
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
     * @throws StateAccessException
     *             Data access error.
     * @return A Tenant object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_TENANT_JSON,
            MediaType.APPLICATION_JSON })
    public Tenant get(@PathParam("id") String id)
            throws StateAccessException {

        if (!authorizer.tenantAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException("Not authorized to view tenants.");
        }

        Tenant tenant = dao.get(id);
        if (tenant == null) {
            throw new NotFoundHttpException(
                    "The requested tenant was not found.");
        }
        tenant.setBaseUri(uriInfo.getBaseUri());
        return tenant;
    }
}
