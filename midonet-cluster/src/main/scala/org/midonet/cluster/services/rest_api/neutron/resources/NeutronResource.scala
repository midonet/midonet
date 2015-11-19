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

package org.midonet.cluster.services.rest_api.neutron.resources

import java.net.URI
import javax.ws.rs.core.{UriBuilder, UriInfo}
import javax.ws.rs.{GET, Path, Produces}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation.ApiResource
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder._
import org.midonet.cluster.rest_api.neutron.models.Neutron
import org.midonet.cluster.rest_api.neutron.resources._
import org.midonet.cluster.services.c3po.C3POStorageManager
import org.midonet.cluster.services.rest_api.MidonetMediaTypes
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@ApiResource(version = 1, name = "neutron")
@Path("neutron")
@RequestScoped
class NeutronResource @Inject() (uriInfo: UriInfo,
                                 resContext: ResourceContext,
                                 c3po: C3POStorageManager,
                                 api: NeutronZoomPlugin) {

    @Path("networks")
    def networkResource: NetworkResource = new NetworkResource(uriInfo, api)

    @Path("subnets")
    def subnetResource: SubnetResource = new SubnetResource(uriInfo, api)

    @Path("ports")
    def portResource: PortResource = new PortResource(uriInfo, api)

    @Path("routers")
    def routerResource: RouterResource = new RouterResource(uriInfo, api)

    @Path("floating_ips")
    def floatingIpResource: FloatingIpResource = new FloatingIpResource(uriInfo, api)

    @Path("security_groups")
    def securityGroupResource: SecurityGroupResource = new SecurityGroupResource(uriInfo, api)

    @Path("security_group_rules")
    def securityGroupRuleResource: SecurityGroupRuleResource = new SecurityGroupRuleResource(uriInfo, api)

    @Path("lb")
    def loadBalancerResource: LBResource = new LBResource(uriInfo, api)

    @Path("firewalls")
    def firewallResource: FirewallResource = new FirewallResource(uriInfo, api)

    @Path("vpnservices")
    def vpnServicesResource: VPNServiceResource = new VPNServiceResource(resContext)

    @GET
    @Produces(Array(MidonetMediaTypes.NEUTRON_JSON_V3)) def getV3: Neutron = {
        val neutron: Neutron = new Neutron
        val baseUri: URI = uriInfo.getBaseUri
        val vpnUri: UriBuilder = UriBuilder.fromUri(baseUri).path("vpnservices")
        neutron.uri = getNeutron(baseUri)
        neutron.networks = getNetworks(baseUri)
        neutron.networkTemplate = getNetworkTemplate(baseUri)
        neutron.subnets = getSubnets(baseUri)
        neutron.subnetTemplate = getSubnetTemplate(baseUri)
        neutron.ports = getPorts(baseUri)
        neutron.portTemplate = getPortTemplate(baseUri)
        neutron.routers = getRouters(baseUri)
        neutron.routerTemplate = getRouterTemplate(baseUri)
        neutron.addRouterInterfaceTemplate = getAddRouterInterfaceTemplate(
            baseUri)
        neutron
            .removeRouterInterfaceTemplate = getRemoveRouterInterfaceTemplate(
            baseUri)
        neutron.floatingIps = getFloatingIps(baseUri)
        neutron.floatingIpTemplate = getFloatingIpTemplate(baseUri)
        neutron.securityGroups = getSecurityGroups(baseUri)
        neutron.securityGroupTemplate = getSecurityGroupTemplate(baseUri)
        neutron.securityGroupRules = getSecurityGroupRules(baseUri)
        neutron.securityGroupRuleTemplate = getSecurityGroupRuleTemplate(
            baseUri)
        neutron.loadBalancer = LBResource.buildLoadBalancer(baseUri)
        neutron.firewalls = getFirewalls(baseUri)
        neutron.firewallTemplate = getFirewallTemplate(baseUri)
        neutron.vpnServices = vpnUri.build()
        neutron.vpnServicesTemplate = neutron.vpnServices.toString + "/{id}"
        neutron
    }
}