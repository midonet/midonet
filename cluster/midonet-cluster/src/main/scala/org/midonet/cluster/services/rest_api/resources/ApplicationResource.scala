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

package org.midonet.cluster.services.rest_api.resources

import javax.ws.rs.core.MediaType
import javax.ws.rs.{GET, Path, Produces}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.Application
import org.midonet.cluster.rest_api.neutron.resources.NeutronResource
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
@Path("/")
class ApplicationResource @Inject()(resContext: ResourceContext,
                                    adRouteResource: AdRouteResource,
                                    bgpResource: BgpResource,
                                    bridgeResource: BridgeResource,
                                    l2insertionResource: L2InsertionResource,
                                    l2serviceResource: L2ServiceResource,
                                    chainResource: ChainResource,
                                    hostResource: HostResource,
                                    loadBalancerResource: LoadBalancerResource,
                                    loginResource: LoginResource,
                                    neutronResource: NeutronResource,
                                    poolResource: PoolResource,
                                    poolMemberResource: PoolMemberResource,
                                    portResource: PortResource,
                                    portGroupResource: PortGroupResource,
                                    routeResource: RouteResource,
                                    routerResource: RouterResource,
                                    ruleResource: RuleResource,
                                    systemStateResource: SystemStateResource,
                                    tunnelZoneResource: TunnelZoneResource,
                                    vipResource: VipResource,
                                    tenantResource: TenantResource)
    extends MidonetResource(resContext) {

    @GET
    @Produces(Array(MediaType.APPLICATION_JSON, APPLICATION_JSON_V5))
    def application: Application = {
        log.debug(s"${getClass.getName} entered on " +
                  s"${resContext.uriInfo.getAbsolutePath}")
        new Application(resContext.uriInfo.getAbsolutePathBuilder.build())
    }

    @Path("ad_routes")
    def adRoutes = adRouteResource

    @Path("bgps")
    def bgps = bgpResource

    @Path("bridges")
    def bridges = bridgeResource

    @Path("l2insertions")
    def l2insertions = l2insertionResource

    @Path("l2services")
    def l2services = l2serviceResource

    @Path("chains")
    def chains = chainResource

    @Path("hosts")
    def hosts = hostResource

    @Path("load_balancers")
    def loadBalancers = loadBalancerResource

    @Path("login")
    def login = loginResource

    @Path("neutron")
    def neutron = neutronResource

    @Path("pools")
    def pools = poolResource

    @Path("pool_members")
    def poolMembers = poolMemberResource

    @Path("ports")
    def ports = portResource

    @Path("port_groups")
    def portGroups = portGroupResource

    @Path("routers")
    def routers = routerResource

    @Path("routes")
    def routes = routeResource

    @Path("rules")
    def rules = ruleResource

    @Path("system_state")
    def systemState = systemStateResource

    @Path("tunnel_zones")
    def tunnelZones = tunnelZoneResource

    @Path("vips")
    def vips = vipResource

    @Path("tenants")
    def tenants = tenantResource

}
