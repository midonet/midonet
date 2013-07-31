/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.codehaus.jackson.map.annotate.JsonView;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.*;
import org.midonet.api.filter.rest_api.ChainResource;
import org.midonet.api.network.rest_api.BridgeResource;
import org.midonet.api.network.rest_api.PortGroupResource;
import org.midonet.api.network.rest_api.RouterResource;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.serialization.ViewMixinProvider;
import org.midonet.api.serialization.Views;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Root resource class for tenants
 */
@RequestScoped
public class TenantResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(TenantResource.class);

    private final AuthService authService;
    private final HttpServletRequest reqContext;
    private final ResourceFactory factory;

    /**
     * Represents the view of the Tenant object used in the API
     */
    public abstract class TenantPublicMixin {

        @JsonView(Views.Public.class)
        abstract String getId();

        @JsonView(Views.Public.class)
        abstract String getName();

        @JsonView(Views.Public.class)
        abstract URI getUri();

        @JsonView(Views.Public.class)
        abstract URI getRouters();

        @JsonView(Views.Public.class)
        abstract URI getBridges();

        @JsonView(Views.Public.class)
        abstract URI getChains();

        @JsonView(Views.Public.class)
        abstract URI getPortGroups();
    }

    static {
        ViewMixinProvider.registerViewMixin(Tenant.class,
                TenantPublicMixin.class);
        ViewMixinProvider.registerMediaType(
                VendorMediaType.APPLICATION_TENANT_JSON);
        ViewMixinProvider.registerMediaType(
                VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON);
    }

    @Inject
    public TenantResource(RestApiConfig config, UriInfo uriInfo,
                          SecurityContext context, AuthService authService,
                          HttpServletRequest reqContext, ResourceFactory factory) {
        super(config, uriInfo, context);
        this.authService = authService;
        this.reqContext = reqContext;
        this.factory = factory;
    }

    /**
     * Bridge resource locator for tenants.
     *
     * @param id
     *            Tenant ID from the request.
     * @returns TenantBridgeResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.BRIDGES)
    public BridgeResource.TenantBridgeResource getBridgeResource(
            @PathParam("id") String id) {
        return factory.getTenantBridgeResource(id);
    }

    /**
     * Chain resource locator for tenants.
     *
     * @param id
     *            Tenant ID from the request.
     * @returns TenantChainResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.CHAINS)
    public ChainResource.TenantChainResource getChainResource(
            @PathParam("id") String id) {
        return factory.getTenantChainResource(id);
    }

    /**
     * Port group resource locator for tenants.
     *
     * @param id
     *            Tenant ID from the request.
     * @returns TenantPortGroupResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORT_GROUPS)
    public PortGroupResource.TenantPortGroupResource getPortGroupResource(
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
    public RouterResource.TenantRouterResource getRouterResource(
            @PathParam("id") String id) {
        return factory.getTenantRouterResource(id);
    }


    /**
     * Handler to get a {@link Tenant] object
     * @return Tenant object
     * @throws AuthException
     */
    @GET
    @PermitAll
    @Path("/{id}")
    @Produces({ VendorMediaType.APPLICATION_TENANT_JSON })
    public Tenant get(@PathParam("id") String id) throws AuthException {
        log.debug("TenantResource.get: entered. id=" + id);

        if (!Authorizer.isAdminOrOwner(context, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this tenant.");
        }

        org.midonet.api.auth.Tenant authTenant = authService.getTenant(id);
        if (authTenant == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        Tenant tenant = new Tenant(authTenant);
        tenant.setBaseUri(getBaseUri());
        return tenant;
    }

    /**
     * Handler to list tenants.
     *
     * @return A list of Tenant objects.
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON })
    public List<Tenant> list() throws AuthException {
        log.debug("TenantResource.list: entered");

        List<Tenant> tenants = new ArrayList<Tenant>();

        List<org.midonet.api.auth.Tenant> authTenants =
                authService.getTenants(this.reqContext);
        if (tenants != null) {
            for (org.midonet.api.auth.Tenant authTenant : authTenants) {
                Tenant tenant = new Tenant(authTenant);
                tenant.setBaseUri(getBaseUri());
                tenants.add(tenant);
            }
        }
        return tenants;
    }
}
