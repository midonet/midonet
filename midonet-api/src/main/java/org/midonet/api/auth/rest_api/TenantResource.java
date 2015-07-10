/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.auth.rest_api;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.fasterxml.jackson.annotation.JsonView;
import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;

import org.slf4j.Logger;

import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.auth.AuthException;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.auth.AuthService;
import org.midonet.cluster.rest_api.ForbiddenHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.models.Tenant;
import org.midonet.cluster.rest_api.serialization.ViewMixinProvider;
import org.midonet.cluster.rest_api.serialization.Views;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Root resource class for tenants
 */
@RequestScoped
public class TenantResource extends AbstractResource {

    private final static Logger log = getLogger(TenantResource.class);

    private final AuthService authService;
    private final HttpServletRequest reqContext;

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
                          HttpServletRequest reqContext) {
        super(config, uriInfo, context, null, null);
        this.authService = authService;
        this.reqContext = reqContext;
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
    public Tenant get(@PathParam("id") String tenantId) throws AuthException {
        log.debug("TenantResource.get: entered. id=" + tenantId);

        if (!authoriser.isAdminOrOwner(tenantId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this tenant.");
        }

        org.midonet.cluster.auth.Tenant tenant = authService.getTenant(tenantId);
        if (tenant == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        return new Tenant(getBaseUri(), tenant.getId(), tenant.getName());
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON })
    public List<Tenant> list() throws AuthException {
        log.debug("TenantResource.list: entered");
        List<org.midonet.cluster.auth.Tenant> authTenants =
                authService.getTenants(this.reqContext);
        List<Tenant> tenants = new ArrayList<>();
        if (authTenants == null) {
            return tenants;
        }
        for (org.midonet.cluster.auth.Tenant authTenant : authTenants) {
            tenants.add(new Tenant(getBaseUri(),
                                   authTenant.getId(),
                                   authTenant.getName()));
        }
        return tenants;
    }
}
