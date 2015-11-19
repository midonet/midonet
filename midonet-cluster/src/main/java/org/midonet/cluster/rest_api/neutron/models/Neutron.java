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
package org.midonet.cluster.rest_api.neutron.models;

import java.net.URI;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import org.midonet.util.version.Since;

public class Neutron {

    public URI uri;

    public URI networks;

    @JsonProperty("network_template")
    public String networkTemplate;

    public URI subnets;

    @JsonProperty("subnet_template")
    public String subnetTemplate;

    public URI ports;

    @JsonProperty("port_template")
    public String portTemplate;

    public URI routers;

    @JsonProperty("router_template")
    public String routerTemplate;

    @JsonProperty("add_router_interface_template")
    public String addRouterInterfaceTemplate;

    @JsonProperty("remove_router_interface_template")
    public String removeRouterInterfaceTemplate;

    @JsonProperty("floating_ips")
    public URI floatingIps;

    @JsonProperty("floating_ip_template")
    public String floatingIpTemplate;

    @JsonProperty("security_groups")
    public URI securityGroups;

    @JsonProperty("security_group_template")
    public String securityGroupTemplate;

    @JsonProperty("security_group_rules")
    public URI securityGroupRules;

    @JsonProperty("security_group_rule_template")
    public String securityGroupRuleTemplate;

    @JsonProperty("load_balancer")
    public LoadBalancer loadBalancer;

    @Since("3")
    @JsonProperty("firewalls")
    public URI firewalls;

    @Since("3")
    @JsonProperty("firewall_template")
    public String firewallTemplate;

    @Since("4")
    @JsonProperty("vpn_services")
    public URI vpnServices;

    @Since("4")
    @JsonProperty("vpn_services_template")
    public String vpnServicesTemplate;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) {
            return true;
        }

        if (!(obj instanceof Neutron)) {
            return false;
        }
        final Neutron other = (Neutron) obj;

        return Objects.equal(uri, other.uri)
               && Objects.equal(networks, other.networks)
               && Objects.equal(networkTemplate, other.networkTemplate)
               && Objects.equal(subnets, other.subnets)
               && Objects.equal(subnetTemplate, other.subnetTemplate)
               && Objects.equal(ports, other.ports)
               && Objects.equal(portTemplate, other.portTemplate)
               && Objects.equal(routers, other.routers)
               && Objects.equal(routerTemplate, other.routerTemplate)
               && Objects.equal(addRouterInterfaceTemplate,
                                other.addRouterInterfaceTemplate)
               && Objects.equal(removeRouterInterfaceTemplate,
                                other.removeRouterInterfaceTemplate)
               && Objects.equal(floatingIps, other.floatingIps)
               && Objects.equal(floatingIpTemplate, other.floatingIpTemplate)
               && Objects.equal(securityGroups, other.securityGroups)
               && Objects.equal(securityGroupTemplate,
                                other.securityGroupTemplate)
               && Objects.equal(securityGroupRules, other.securityGroupRules)
               && Objects.equal(securityGroupRuleTemplate,
                                other.securityGroupRuleTemplate)
               && Objects.equal(loadBalancer, other.loadBalancer)
               && Objects.equal(firewalls, other.firewalls)
               && Objects.equal(firewallTemplate, other.firewallTemplate)
               && Objects.equal(vpnServices, other.vpnServices)
               && Objects.equal(vpnServicesTemplate, other.vpnServicesTemplate);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(uri, networks, networkTemplate, subnets,
                                subnetTemplate, ports, portTemplate, routers,
                                routerTemplate, addRouterInterfaceTemplate,
                                removeRouterInterfaceTemplate, floatingIps,
                                floatingIpTemplate, securityGroups,
                                securityGroupTemplate,
                                securityGroupRules, securityGroupRuleTemplate,
                                loadBalancer, firewalls, firewallTemplate,
                                vpnServices, vpnServicesTemplate);
    }

    @Override
    public String toString() {

        return MoreObjects.toStringHelper(this)
            .add("uri", uri)
            .add("networks", networks)
            .add("networkTemplate", networkTemplate)
            .add("subnets", subnets)
            .add("subnetTemplate", subnetTemplate)
            .add("ports", ports)
            .add("portTemplate", portTemplate)
            .add("routers", routers)
            .add("routerTemplate", routerTemplate)
            .add("addRouterInterfaceTemplate", addRouterInterfaceTemplate)
            .add("removeRouterInterfaceTemplate", removeRouterInterfaceTemplate)
            .add("floatingIps", floatingIps)
            .add("floatingIpTemplate", floatingIpTemplate)
            .add("securityGroups", securityGroups)
            .add("securityGroupTemplate", securityGroupTemplate)
            .add("securityGroupRules", securityGroupRules)
            .add("securityGroupRuleTemplate", securityGroupRuleTemplate)
            .add("loadBalancer", loadBalancer)
            .add("firewalls", firewalls)
            .add("firewallTemplate", firewallTemplate)
            .add("vpnservices", vpnServices)
            .add("vpnservicesTemplate", vpnServicesTemplate).toString();
    }
}
