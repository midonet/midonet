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

/**
 * The top application resource class.
 *
 * @version 1.6 15 Nov 2011
 * @author Ryu Ishimoto
 */
@Path(UriManager.ROOT)
public class ApplicationResource {

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
     * BGP resource locator.
     *
     * @returns BgpResource object to handle sub-resource requests.
     */
    @Path(UriManager.BGP)
    public BgpResource getBgpResource() {
        return new BgpResource();
    }

    /**
     * Ad route resource locator.
     *
     * @returns AdRouteResource object to handle sub-resource requests.
     */
    @Path(UriManager.AD_ROUTES)
    public AdRouteResource getAdRouteResource() {
        return new AdRouteResource();
    }

    /**
     * VPN resource locator.
     *
     * @returns VpnResource object to handle sub-resource requests.
     */
    @Path(UriManager.VPN)
    public VpnResource getVpnResource() {
        return new VpnResource();
    }

    /**
     * Handler for getting root application resources.
     *
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param config
     *            AppConfig object that holds the application configurations.
     * @throws InvalidConfigException
     *             Missing configuration parameter.
     * @returns An Application object.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON })
    public Application get(@Context UriInfo uriInfo, @Context AppConfig config)
            throws InvalidConfigException {
        Application a = new Application(uriInfo.getBaseUri());
        a.setVersion(config.getVersion());
        return a;
    }
}
