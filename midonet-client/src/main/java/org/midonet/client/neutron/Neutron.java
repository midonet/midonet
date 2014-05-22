/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.client.neutron;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonProperty;

import java.net.URI;

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

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Neutron)) return false;
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
                && Objects.equal(
                    securityGroupTemplate, other.securityGroupTemplate)
                && Objects.equal(securityGroupRules, other.securityGroupRules)
                && Objects.equal(
                    securityGroupRuleTemplate, other.securityGroupRuleTemplate);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(uri, networks, networkTemplate, subnets,
                subnetTemplate, ports, portTemplate, routers,
                routerTemplate, addRouterInterfaceTemplate,
                removeRouterInterfaceTemplate, floatingIps, floatingIpTemplate,
                securityGroups, securityGroupTemplate, securityGroupRules,
                securityGroupRuleTemplate);
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
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
                .add("removeRouterInterfaceTemplate",
                        removeRouterInterfaceTemplate)
                .add("floatingIps", floatingIps)
                .add("floatingIpTemplate", floatingIpTemplate)
                .add("securityGroups", securityGroups)
                .add("securityGroupTemplate", securityGroupTemplate)
                .add("securityGroupRules", securityGroupRules)
                .add("securityGroupRuleTemplate",
                        securityGroupRuleTemplate).toString();
    }
}
