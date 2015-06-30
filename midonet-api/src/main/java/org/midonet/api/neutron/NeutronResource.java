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
package org.midonet.api.neutron;

import java.net.URI;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.cluster.auth.AuthRole;
import org.midonet.api.neutron.loadbalancer.LBResource;
import org.midonet.api.neutron.loadbalancer.LBUriBuilder;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.Neutron;

@Path(NeutronUriBuilder.NEUTRON)
public class NeutronResource extends AbstractResource {

    private final NeutronResourceFactory factory;

    @Inject
    public NeutronResource(RestApiConfig config, UriInfo uriInfo,
                           SecurityContext context,
                           NeutronResourceFactory factory) {
        super(config, uriInfo, context, null, null);
        this.factory = factory;
    }

    @Path(NeutronUriBuilder.NETWORKS)
    public NetworkResource getNetworkResource() {
        return factory.getNeutronNetworkResource();
    }

    @Path(NeutronUriBuilder.SUBNETS)
    public SubnetResource getSubnetResource() {
        return factory.getNeutronSubnetResource();
    }

    @Path(NeutronUriBuilder.PORTS)
    public PortResource getPortResource() {
        return factory.getNeutronPortResource();
    }

    @Path(NeutronUriBuilder.ROUTERS)
    public RouterResource getRouterResource() {
        return factory.getNeutronRouterResource();
    }

    @Path(NeutronUriBuilder.FLOATING_IPS)
    public FloatingIpResource getFloatingIpResource() {
        return factory.getNeutronFloatingIpResource();
    }

    @Path(NeutronUriBuilder.SECURITY_GROUPS)
    public SecurityGroupResource getSecurityGroupResource() {
        return factory.getNeutronSecurityGroupResource();
    }

    @Path(NeutronUriBuilder.SECURITY_GROUP_RULES)
    public SecurityGroupRuleResource getSecurityGroupRuleResource() {
        return factory.getNeutronSecurityGroupRuleResource();
    }

    @Path(LBUriBuilder.LB)
    public LBResource getLoadBalancerResource() {
        return factory.getLoadBalancerResource();
    }

    /**
     * Handler to getting a neutron object.
     *
     * @return A Neutron object.
     */
    @GET
    @RolesAllowed(AuthRole.ADMIN)
    @Produces({NeutronMediaType.NEUTRON_JSON_V1,
               NeutronMediaType.NEUTRON_JSON_V2})
    public Neutron get() {

        Neutron neutron = new Neutron();

        URI baseUri = getBaseUri();
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
        return neutron;
    }
}
