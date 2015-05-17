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
                                    vipResource: VIPResource)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_JSON,
                    APPLICATION_JSON_V5))
    def application: Application = {
        log.debug(s"${getClass.getName} entered on ${uriInfo.getAbsolutePath}")
        new Application(uriInfo.getAbsolutePathBuilder.build())
    }

    @Path("bridges")
    def bridges = bridgeResource

    @Path("chains")
    def chains = chainResource

    @Path("hosts")
    def hosts = hostResource

    @Path("load_balancers")
    def loadBalancers = loadBalancerResource

    @Path("login")
    def login = loginResource

    @Path("pools")
    def pools = poolResource

    @Path("pool_members")
    def poolMembers = poolMemberResource

    @Path("ports")
    def ports = portResource

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

}
