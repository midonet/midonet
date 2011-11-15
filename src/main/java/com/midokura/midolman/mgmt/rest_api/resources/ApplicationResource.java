/*
 * @(#)ApplicationResource        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.midokura.midolman.mgmt.data.dto.Application;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;

@Path("/")
public class ApplicationResource {

    private final static String adminResourcePath = "/admin";
    private final static String tenantResourcePath = "/tenants";

    /**
     * Admin resource locator.
     */
    @Path(adminResourcePath)
    public AdminResource getAdminResource() {
        return new AdminResource();
    }

    /**
     * Tenant resource locator.
     */
    @Path(tenantResourcePath)
    public TenantResource getTenantResource() {
        return new TenantResource();
    }

    @GET
    @Produces({ VendorMediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON })
    public Application get(@Context UriInfo uriInfo)
            throws InvalidConfigException {
        Application a = new Application();
        AppConfig config = AppConfig.getConfig();
        a.setVersion(config.getVersion());
        a.setUri(uriInfo.getAbsolutePath().toString());
        a.setAdmin(adminResourcePath);
        a.setTenant(tenantResourcePath);
        return a;
    }
}
