/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.neutron.loadbalancer;

import java.net.URI;

import javax.ws.rs.Path;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.loadbalancer.LoadBalancer;

@Path(LBUriBuilder.LB)
public class LBResource extends AbstractResource {

    private final LBResourceFactory factory;

    @Inject
    public LBResource(RestApiConfig config, UriInfo uriInfo,
                      SecurityContext context,
                      LBResourceFactory factory) {
        super(config, uriInfo, context, null);
        this.factory = factory;
    }

    @Path(LBUriBuilder.HEALTH_MONITORS)
    public HealthMonitorResource getHealthMonitorResource() {
        return factory.getHealthMonitorResource();
    }

    @Path(LBUriBuilder.MEMBERS)
    public MemberResource getMemberResource() {
        return factory.getMemberResource();
    }

    @Path(LBUriBuilder.POOLS)
    public PoolResource getPoolResource() {
        return factory.getPoolResource();
    }

    @Path(LBUriBuilder.VIPS)
    public VipResource getVipResource() {
        return factory.getVipResource();
    }

    public static LoadBalancer buildLoadBalancer(URI baseUri) {
        LoadBalancer lb = new LoadBalancer();

        lb.healthMonitors = LBUriBuilder.getHealthMonitors(baseUri);
        lb.healthMonitorTemplate = LBUriBuilder.getHealthMonitorTemplate(
            baseUri);
        lb.members = LBUriBuilder.getMembers(baseUri);
        lb.memberTemplate = LBUriBuilder.getMemberTemplate(baseUri);
        lb.pools = LBUriBuilder.getPools(baseUri);
        lb.poolTemplate = LBUriBuilder.getPoolTemplate(baseUri);
        lb.vips = LBUriBuilder.getVips(baseUri);
        lb.vipTemplate = LBUriBuilder.getVipTemplate(baseUri);

        return lb;
    }
}
