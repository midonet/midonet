/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.midokura.midolman.mgmt.config.RestApiConfig;
import com.midokura.midolman.mgmt.data.dto.Application;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

/**
 * The top application resource class.
 */
@Path(ResourceUriBuilder.ROOT)
public class ApplicationResource {

    private final static Logger log =
            LoggerFactory.getLogger(ApplicationResource.class);

    private final RestApiConfig config;

    @Inject
    public ApplicationResource(RestApiConfig config) {
        this.config = config;
    }

    /**
     * Tenant resource locator.
     *
     * @return TenantResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.TENANTS)
    public TenantResource getTenantResource() {
        return new TenantResource();
    }

    /**
     * Router resource locator.
     *
     * @return RouterResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.ROUTERS)
    public RouterResource getRouterResource() {
        return new RouterResource();
    }

    /**
     * Bridge resource locator.
     *
     * @return BridgeResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.BRIDGES)
    public BridgeResource getBridgeResource() {
        return new BridgeResource();
    }

    /**
     * Port resource locator.
     *
     * @return PortResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.PORTS)
    public PortResource getPortResource() {
        return new PortResource();
    }

    /**
     * Route resource locator.
     *
     * @return RouteResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.ROUTES)
    public RouteResource getRouteResource() {
        return new RouteResource();
    }

    /**
     * Chain resource locator.
     *
     * @return ChainResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.CHAINS)
    public ChainResource getChainResource() {
        return new ChainResource();
    }

    /**
     * PortGroups resource locator.
     *
     * @return ChainResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.PORT_GROUPS)
    public PortGroupResource getPortGroupResource() {
        return new PortGroupResource();
    }

    /**
     * Rule resource locator.
     *
     * @return RuleResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.RULES)
    public RuleResource getRuleResource() {
        return new RuleResource();
    }

    /**
     * BGP resource locator.
     *
     * @return BgpResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.BGP)
    public BgpResource getBgpResource() {
        return new BgpResource();
    }

    /**
     * Ad route resource locator.
     *
     * @return AdRouteResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.AD_ROUTES)
    public AdRouteResource getAdRouteResource() {
        return new AdRouteResource();
    }

    /**
     * VPN resource locator.
     *
     * @return VpnResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.VPN)
    public VpnResource getVpnResource() {
        return new VpnResource();
    }

    /**
     * Host resource locator
     *
     * @return HostResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.HOSTS)
    public HostResource getHostResource() {
        return new HostResource();
    }

    /**
     * Host resource locator
     *
     * @return HostResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.METRICS)
    public MonitoringResource getMonitoringQueryResource() {
        return new MonitoringResource();
    }

    /**
     * Handler for getting root application resources.
     *
     * @param uriInfo
     *            Object that holds the request URI data.
     * @throws InvalidConfigException
     *             Missing configuration parameter.
     * @return An Application object.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON })
    public Application get(@Context UriInfo uriInfo)
            throws InvalidConfigException {
        log.debug("ApplicationResource: entered: " + uriInfo);

        Application a = new Application(uriInfo.getBaseUri());
        a.setVersion(config.getVersion());

        log.debug("ApplicationResource: existing: " + a);
        return a;
    }
}
