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
                subnetTemplate, ports, portTemplate, securityGroups,
                securityGroupTemplate, securityGroupRules,
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
                .add("securityGroups", securityGroups)
                .add("securityGroupTemplate", securityGroupTemplate)
                .add("securityGroupRules", securityGroupRules)
                .add("securityGroupRuleTemplate",
                        securityGroupRuleTemplate).toString();
    }
}
