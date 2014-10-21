/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.api.neutron.loadbalancer;

import java.net.URI;

import javax.ws.rs.Path;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.loadbalancer.LoadBalancer;

public class LBResource extends AbstractResource {

    private final LBResourceFactory factory;

    @Inject
    public LBResource(RestApiConfig config, UriInfo uriInfo,
                      SecurityContext context,
                      LBResourceFactory factory) {
        super(config, uriInfo, context, null);
        this.factory = factory;
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
        lb.poolHealthMonitor = LBUriBuilder.getPoolHealthMonitor(baseUri);

        return lb;
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

    @Path(LBUriBuilder.POOL_HEALTH_MONITOR)
    public PoolHealthMonitorResource getPoolHealthMonitorResource() {
        return factory.getPoolHealthMonitorResource();
    }
}
