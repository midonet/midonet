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
import javax.ws.rs.core.UriInfo
import javax.ws.rs.{GET, Path, Produces}

import com.google.inject.Inject
import org.midonet.cluster.data.neutron.models.Neutron
import org.midonet.cluster.rest_api.neutron.NeutronResourceUris._
import org.midonet.cluster.rest_api.neutron.models.{Neutron, LoadBalancer}
import org.midonet.cluster.services.rest_api.neutron.NeutronMediaType
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin

@Path(NEUTRON) class NeutronResource @Inject() (uriInfo: UriInfo,
                                                zoomPlugin: NeutronZoomPlugin) {

    private def buildLoadBalancer(baseUri: URI): LoadBalancer = {
        val lb: LoadBalancer = new LoadBalancer
        lb.healthMonitors = getUri(baseUri, HEALTH_MONITORS)
        lb.healthMonitorTemplate = getUri(baseUri, HEALTH_MONITORS, ID_TOKEN)
            .toString
        lb.members = getUri(baseUri, MEMBERS)
        lb.memberTemplate = getUri(baseUri, MEMBERS, ID_TOKEN).toString
        lb.pools = getUri(baseUri, POOLS)
        lb.poolTemplate = getUri(baseUri, POOLS, ID_TOKEN).toString
        lb.vips = getUri(baseUri, VIPS)
        lb.vipTemplate = getUri(baseUri, VIPS, ID_TOKEN).toString
        lb.poolHealthMonitor = getUri(baseUri, HEALTH_MONITORS)
        lb
    }

    @Path(NETWORKS)
    def getNetworkResource: NetworkResource = {
        new NetworkResource(uriInfo, zoomPlugin)
    }

    @Path(SUBNETS)
    def getSubnetResource: SubnetResource = {
        new SubnetResource(uriInfo, zoomPlugin)
    }

    @Path(PORTS)
    def getPortResource: PortResource = {
        new PortResource(uriInfo, zoomPlugin)
    }

    @Path(ROUTERS)
    def getRouterResource: RouterResource = {
        new RouterResource(uriInfo, zoomPlugin)
    }

    @Path(FLOATING_IPS)
    def getFloatingIpResource: FloatingIpResource = {
        new FloatingIpResource(uriInfo, zoomPlugin)
    }

    @Path(SECURITY_GROUPS)
    def getSecurityGroupResource: SecurityGroupResource = {
        new SecurityGroupResource(uriInfo, zoomPlugin)
    }

    @Path(SECURITY_GROUP_RULES)
    def getSecurityGroupRuleResource: SecurityGroupRuleResource = {
        new SecurityGroupRuleResource(uriInfo, zoomPlugin)
    }

    @Path(LB) def getLoadBalancerResource: LBResource = {
        new LBResource(uriInfo, zoomPlugin)
    }

    @GET
    @Produces(Array(NeutronMediaType.NEUTRON_JSON_V1,
                    NeutronMediaType.NEUTRON_JSON_V2))
    def get: Neutron = {
        val neutron: Neutron = new Neutron
        val baseUri: URI = uriInfo.getBaseUri
        neutron.uri = getUri(baseUri, NEUTRON)
        neutron.networks = getUri(baseUri, NETWORKS)
        neutron.networkTemplate = getUri(baseUri, NETWORKS, ID_TOKEN).toString
        neutron.subnets = getUri(baseUri, SUBNETS)
        neutron.subnetTemplate = getUri(baseUri, SUBNETS, ID_TOKEN).toString
        neutron.ports = getUri(baseUri, PORTS)
        neutron.portTemplate = getUri(baseUri, PORTS, ID_TOKEN).toString
        neutron.routers = getUri(baseUri, ROUTERS)
        neutron.routerTemplate = getUri(baseUri, ROUTERS, ID_TOKEN).toString
        neutron.floatingIps = getUri(baseUri, FLOATING_IPS)
        neutron.floatingIpTemplate = getUri(baseUri, FLOATING_IPS, ID_TOKEN)
            .toString
        neutron.securityGroups = getUri(baseUri, SECURITY_GROUPS)
        neutron.securityGroupTemplate = getUri(baseUri, SECURITY_GROUPS,
                                               ID_TOKEN).toString
        neutron.securityGroupRules = getUri(baseUri, SECURITY_GROUP_RULES)
        neutron.securityGroupRuleTemplate = getUri(baseUri,
                                                   SECURITY_GROUP_RULES,
                                                   ID_TOKEN).toString
        neutron.loadBalancer = buildLoadBalancer(baseUri)
        neutron.removeRouterInterfaceTemplate = neutron.routerTemplate + "/" +
                                                REMOVE_ROUTER_INTF
        neutron.addRouterInterfaceTemplate = neutron.routerTemplate + "/" +
                                             ADD_ROUTER_INTF
        neutron
    }
}