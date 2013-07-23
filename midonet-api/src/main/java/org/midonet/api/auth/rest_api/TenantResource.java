/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.codehaus.jackson.map.annotate.JsonView;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthException;
import org.midonet.api.auth.AuthService;
import org.midonet.api.auth.Tenant;
import org.midonet.api.serialization.ViewMixinProvider;
import org.midonet.api.serialization.Views;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import java.util.List;

/**
 * Root resource class for tenants
 */
@RequestScoped
public class TenantResource {

    private final static Logger log = LoggerFactory
            .getLogger(TenantResource.class);

    private final AuthService authService;
    private final HttpServletRequest context;

    /**
     * Represents the view of the Tenant object used in the API
     */
    public interface TenantPublicMixin {

        @JsonView(Views.Public.class)
        String getId();

        @JsonView(Views.Public.class)
        String getName();

    }

    static {
        ViewMixinProvider.registerViewMixin(Tenant.class,
                TenantPublicMixin.class);
        ViewMixinProvider.registerMediaType(
                VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON);
    }

    @Inject
    public TenantResource(AuthService authService, HttpServletRequest context) {
        this.authService = authService;
        this.context = context;
    }

    /**
     * Handler to list tenants.
     *
     * @return A list of Tenant objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON })
    public List<Tenant> list() throws AuthException {
        log.debug("TenantResource.list: entered");

        return authService.getTenants(this.context);
    }
}
