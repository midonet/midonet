/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.data.dto.Application;
import com.midokura.midolman.mgmt.http.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.RestApiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

/**
 * The top application resource class.
 */
@RequestScoped
@Path(ResourceUriBuilder.ROOT)
public class ApplicationResource {

    private final static Logger log =
            LoggerFactory.getLogger(ApplicationResource.class);

    private final UriInfo uriInfo;
    private final RestApiConfig config;
    private final ResourceFactory factory;

    @Inject
    public ApplicationResource(UriInfo uriInfo, RestApiConfig config,
                               ResourceFactory factory) {
        this.uriInfo = uriInfo;
        this.config = config;
        this.factory = factory;
    }

    /**
     * Tenant resource locator.
     *
     * @return TenantResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.TENANTS)
    public TenantResource getTenantResource() {
        return factory.getTenantResource();
    }

    /**
     * Router resource locator.
     *
     * @return RouterResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.ROUTERS)
    public RouterResource getRouterResource() {
        return factory.getRouterResource();
    }

    /**
     * Bridge resource locator.
     *
     * @return BridgeResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.BRIDGES)
    public BridgeResource getBridgeResource() {
        return factory.getBridgeResource();
    }

    /**
     * Port resource locator.
     *
     * @return PortResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.PORTS)
    public PortResource getPortResource() {
        return factory.getPortResource();
    }

    /**
     * Route resource locator.
     *
     * @return RouteResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.ROUTES)
    public RouteResource getRouteResource() {
        return factory.getRouteResource();
    }

    /**
     * Chain resource locator.
     *
     * @return ChainResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.CHAINS)
    public ChainResource getChainResource() {
        return factory.getChainResource();
    }

    /**
     * PortGroups resource locator.
     *
     * @return ChainResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.PORT_GROUPS)
    public PortGroupResource getPortGroupResource() {
        return factory.getPortGroupResource();
    }

    /**
     * Rule resource locator.
     *
     * @return RuleResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.RULES)
    public RuleResource getRuleResource() {
        return factory.getRuleResource();
    }

    /**
     * BGP resource locator.
     *
     * @return BgpResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.BGP)
    public BgpResource getBgpResource() {
        return factory.getBgpResource();
    }

    /**
     * Ad route resource locator.
     *
     * @return AdRouteResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.AD_ROUTES)
    public AdRouteResource getAdRouteResource() {
        return factory.getAdRouteResource();
    }

    /**
     * VPN resource locator.
     *
     * @return VpnResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.VPN)
    public VpnResource getVpnResource() {
        return factory.getVpnResource();
    }

    /**
     * Host resource locator
     *
     * @return HostResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.HOSTS)
    public HostResource getHostResource() {
        return factory.getHostResource();
    }

    /**
     * Host resource locator
     *
     * @return HostResource object to handle sub-resource requests.
     */
    @Path(ResourceUriBuilder.METRICS)
    public MonitoringResource getMonitoringQueryResource() {
        return factory.getMonitoringQueryResource();
    }

    /**
     * Handler for getting root application resources.
     *
     * @return An Application object.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON })
    public Application get() {
        log.debug("ApplicationResource: entered: " + uriInfo);

        Application a = new Application(uriInfo.getBaseUri());
        a.setVersion(config.getVersion());

        log.debug("ApplicationResource: existing: " + a);
        return a;
    }
}
