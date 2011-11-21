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
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;

@Path(UriManager.ROOT)
public class ApplicationResource {

    /**
     * Admin resource locator.
     * 
     * @returns AdminResource object to handle sub-resource requests.
     */
    @Path(UriManager.ADMIN)
    public AdminResource getAdminResource() {
        return new AdminResource();
    }

    /**
     * Tenant resource locator.
     * 
     * @returns TenantResource object to handle sub-resource requests.
     */
    @Path(UriManager.TENANTS)
    public TenantResource getTenantResource() {
        return new TenantResource();
    }

    /**
     * Router resource locator.
     * 
     * @returns RouterResource object to handle sub-resource requests.
     */
    @Path(UriManager.ROUTERS)
    public RouterResource getRouterResource() {
        return new RouterResource();
    }

    /**
     * Bridge resource locator.
     * 
     * @returns BridgeResource object to handle sub-resource requests.
     */
    @Path(UriManager.BRIDGES)
    public BridgeResource getBridgeResource() {
        return new BridgeResource();
    }

    /**
     * Port resource locator.
     * 
     * @returns PortResource object to handle sub-resource requests.
     */
    @Path(UriManager.PORTS)
    public PortResource getPortResource() {
        return new PortResource();
    }

    /**
     * VIF resource locator.
     * 
     * @returns VifResource object to handle sub-resource requests.
     */
    @Path(UriManager.VIFS)
    public VifResource getVifResource() {
        return new VifResource();
    }

    /**
     * Route resource locator.
     * 
     * @returns RouteResource object to handle sub-resource requests.
     */
    @Path(UriManager.ROUTES)
    public RouteResource getRouteResource() {
        return new RouteResource();
    }

    /**
     * Chain resource locator.
     * 
     * @returns ChainResource object to handle sub-resource requests.
     */
    @Path(UriManager.CHAINS)
    public ChainResource getChainResource() {
        return new ChainResource();
    }

    /**
     * Rule resource locator.
     * 
     * @returns RuleResource object to handle sub-resource requests.
     */
    @Path(UriManager.RULES)
    public RuleResource getRuleResource() {
        return new RuleResource();
    }

    /**
     * Handler for getting root application resources.
     * 
     * @param uriInfo
     *            Object that holds the request URI data.
     * @throws InvalidConfigException
     *             Missing configuration parameter.
     * @returns An Application object.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON })
    public Application get(@Context UriInfo uriInfo)
            throws InvalidConfigException {
        Application a = new Application(uriInfo.getBaseUri());
        AppConfig config = AppConfig.getConfig();
        a.setVersion(config.getVersion());
        return a;
    }
}
