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

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;

import org.midonet.cluster.rest_api.annotation.ApiResource;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.Neutron;
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin;

@ApiResource(version = 1)
@Path("neutron")
@RequestScoped
public class NeutronResource {

    private final UriInfo uriInfo;
    private final NeutronZoomPlugin api;

    @Inject
    public NeutronResource(UriInfo uriInfo, NeutronZoomPlugin api) {
        this.uriInfo = uriInfo;
        this.api = api;
    }

    @Path(NeutronUriBuilder.NETWORKS)
    public NetworkResource getNetworkResource() {
        return new NetworkResource(uriInfo, api);
    }

    @Path(NeutronUriBuilder.SUBNETS)
    public SubnetResource getSubnetResource() {
        return new SubnetResource(uriInfo, api);
    }

    @Path(NeutronUriBuilder.PORTS)
    public PortResource getPortResource() {
        return new PortResource(uriInfo, api);
    }

    @Path(NeutronUriBuilder.ROUTERS)
    public RouterResource getRouterResource() {
        return new RouterResource(uriInfo, api);
    }

    @Path(NeutronUriBuilder.FLOATING_IPS)
    public FloatingIpResource getFloatingIpResource() {
        return new FloatingIpResource(uriInfo, api);
    }

    @Path(NeutronUriBuilder.SECURITY_GROUPS)
    public SecurityGroupResource getSecurityGroupResource() {
        return new SecurityGroupResource(uriInfo, api);
    }

    @Path(NeutronUriBuilder.SECURITY_GROUP_RULES)
    public SecurityGroupRuleResource getSecurityGroupRuleResource() {
        return new SecurityGroupRuleResource(uriInfo, api);
    }

    @Path(NeutronUriBuilder.LB)
    public LBResource getLoadBalancerResource() {
        return new LBResource(uriInfo, api);
    }

    @Path(NeutronUriBuilder.FIREWALLS)
    public FirewallResource getFirewallResource() {
        return new FirewallResource(uriInfo, api);
    }

    @GET
    @Produces(NeutronMediaType.NEUTRON_JSON_V3)
    public Neutron get() {

        Neutron neutron = new Neutron();

        URI baseUri = uriInfo.getBaseUri();
        neutron.uri = NeutronUriBuilder.getNeutron(baseUri);
        neutron.networks = NeutronUriBuilder.getNetworks(baseUri);
        neutron.networkTemplate = NeutronUriBuilder.getNetworkTemplate(
            baseUri);
        neutron.subnets = NeutronUriBuilder.getSubnets(baseUri);
        neutron.subnetTemplate = NeutronUriBuilder.getSubnetTemplate(baseUri);
        neutron.ports = NeutronUriBuilder.getPorts(baseUri);
        neutron.portTemplate = NeutronUriBuilder.getPortTemplate(baseUri);
        neutron.routers = NeutronUriBuilder.getRouters(baseUri);
        neutron.routerTemplate = NeutronUriBuilder.getRouterTemplate(baseUri);
        neutron.addRouterInterfaceTemplate =
            NeutronUriBuilder.getAddRouterInterfaceTemplate(baseUri);
        neutron.removeRouterInterfaceTemplate =
            NeutronUriBuilder.getRemoveRouterInterfaceTemplate(baseUri);
        neutron.floatingIps = NeutronUriBuilder.getFloatingIps(baseUri);
        neutron.floatingIpTemplate = NeutronUriBuilder.getFloatingIpTemplate(
            baseUri);
        neutron.securityGroups = NeutronUriBuilder.getSecurityGroups(baseUri);
        neutron.securityGroupTemplate =
            NeutronUriBuilder.getSecurityGroupTemplate(baseUri);
        neutron.securityGroupRules =
            NeutronUriBuilder.getSecurityGroupRules(baseUri);
        neutron.securityGroupRuleTemplate =
            NeutronUriBuilder.getSecurityGroupRuleTemplate(baseUri);
        neutron.loadBalancer = LBResource.buildLoadBalancer(baseUri);
        neutron.firewalls = NeutronUriBuilder.getFirewalls(baseUri);
        neutron.firewallTemplate = NeutronUriBuilder.getFirewallTemplate(
            baseUri);
        return neutron;
    }

}
