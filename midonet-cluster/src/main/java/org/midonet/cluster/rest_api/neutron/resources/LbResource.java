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
package org.midonet.cluster.rest_api.neutron.resources;

import java.net.URI;

import javax.ws.rs.Path;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.LoadBalancer;
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin;

public class LbResource {

    private final UriInfo uriInfo;
    private final NeutronZoomPlugin api;

    @Inject
    public LbResource(UriInfo uriInfo, NeutronZoomPlugin api) {
        this.uriInfo = uriInfo;
        this.api = api;
    }

    public static LoadBalancer buildLoadBalancer(URI baseUri) {
        LoadBalancer lb = new LoadBalancer();

        lb.healthMonitors = NeutronUriBuilder.getHealthMonitors(baseUri);
        lb.healthMonitorTemplate = NeutronUriBuilder.getHealthMonitorTemplate(
            baseUri);
        lb.members = NeutronUriBuilder.getMembers(baseUri);
        lb.memberTemplate = NeutronUriBuilder.getMemberTemplate(baseUri);
        lb.pools = NeutronUriBuilder.getPools(baseUri);
        lb.poolTemplate = NeutronUriBuilder.getPoolTemplate(baseUri);
        lb.vips = NeutronUriBuilder.getVips(baseUri);
        lb.vipTemplate = NeutronUriBuilder.getVipTemplate(baseUri);
        lb.poolHealthMonitor = NeutronUriBuilder.getPoolHealthMonitor(baseUri);

        return lb;
    }

    @Path(NeutronUriBuilder.HEALTH_MONITORS)
    public HealthMonitorResource getHealthMonitorResource() {
        return new HealthMonitorResource(uriInfo, api);
    }

    @Path(NeutronUriBuilder.MEMBERS)
    public MemberResource getMemberResource() {
        return new MemberResource(uriInfo, api);
    }

    @Path(NeutronUriBuilder.POOLS)
    public PoolResource getPoolResource() {
        return new PoolResource(uriInfo, api);
    }

    @Path(NeutronUriBuilder.VIPS)
    public VipResource getVipResource() {
        return new VipResource(uriInfo, api);
    }

    @Path(NeutronUriBuilder.POOL_HEALTH_MONITOR)
    public PoolHealthMonitorResource getPoolHealthMonitorResource() {
        return new PoolHealthMonitorResource();
    }
}
