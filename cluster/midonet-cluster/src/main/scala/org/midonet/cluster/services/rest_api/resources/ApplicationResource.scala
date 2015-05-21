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

import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.UriInfo
import javax.ws.rs.{GET, Path, Produces}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.Application
import org.midonet.cluster.rest_api.models.ResourceUris._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RequestScoped
@Path("/")
class ApplicationResource @Inject()(backend: MidonetBackend,
                                    uriInfo: UriInfo,
                                    bridgeResource: BridgeResource,
                                    chainResource: ChainResource,
                                    hostResource: HostResource,
                                    loadBalancerResource: LoadBalancerResource,
                                    loginResource: LoginResource,
                                    poolResource: PoolResource,
                                    poolMemberResource: PoolMemberResource,
                                    portResource: PortResource,
                                    routeResource: RouteResource,
                                    routerResource: RouterResource,
                                    ruleResource: RuleResource,
                                    systemStateResource: SystemStateResource,
                                    tunnelZoneResource: TunnelZoneResource,
                                    vipResource: VIPResource,
                                    tenantResource: TenantResource)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_JSON,
                    APPLICATION_JSON_V5))
    def application: Application = {
        log.debug(s"${getClass.getName} entered on ${uriInfo.getAbsolutePath}")
        new Application(uriInfo.getAbsolutePathBuilder.build())
    }

    @Path(BRIDGES)
    def bridges = bridgeResource

    @Path(CHAINS)
    def chains = chainResource

    @Path(HOSTS)
    def hosts = hostResource

    @Path(LOAD_BALANCERS)
    def loadBalancers = loadBalancerResource

    @Path("login")
    def login = loginResource

    @Path(POOLS)
    def pools = poolResource

    @Path(POOL_MEMBERS)
    def poolMembers = poolMemberResource

    @Path(PORTS)
    def ports = portResource

    @Path(ROUTERS)
    def routers = routerResource

    @Path(ROUTES)
    def routes = routeResource

    @Path(RULES)
    def rules = ruleResource

    @Path(SYSTEM_STATE)
    def systemState = systemStateResource

    @Path(TUNNEL_ZONES)
    def tunnelZones = tunnelZoneResource

    @Path(VIPS)
    def vips = vipResource

    @Path(TENANTS)
    def tenants = tenantResource

}
