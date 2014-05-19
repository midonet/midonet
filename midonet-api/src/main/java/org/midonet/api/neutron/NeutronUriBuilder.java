/*
* Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
*/
package org.midonet.api.neutron;


import org.midonet.api.ResourceUriBuilder;

import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.UUID;

public class NeutronUriBuilder {

    public final static String NETWORKS = "/networks";
    public final static String SUBNETS = "/subnets";
    public final static String PORTS = "/ports";
    public final static String SECURITY_GROUPS = "/security_groups";
    public final static String SECURITY_GROUPS_RULES = "/security_group_rules";

    public static URI getNeutron(URI baseUri) {
        return UriBuilder.fromUri(ResourceUriBuilder.getRoot(
                baseUri)).path(ResourceUriBuilder.NEUTRON).build();
    }

    // Network
    public static URI getNetworks(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri)).path(NETWORKS).build();
    }

    public static URI getNetwork(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
                getNetworks(baseUri)).path(id.toString()).build();
    }

    public static String getNetworkTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(getNetworks(baseUri));
    }

    // Subnet
    public static URI getSubnets(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri)).path(SUBNETS).build();
    }

    public static URI getSubnet(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
                getSubnets(baseUri)).path(id.toString()).build();
    }

    public static String getSubnetTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(getSubnets(baseUri));
    }

    // Ports
    public static URI getPorts(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri)).path(PORTS).build();
    }

    public static URI getPort(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
                getPorts(baseUri)).path(id.toString()).build();
    }

    public static String getPortTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(getPorts(baseUri));
    }

    // Security Groups
    public static URI getSecurityGroups(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri)).path(
                SECURITY_GROUPS).build();
    }

    public static URI getSecurityGroup(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
                getSecurityGroups(baseUri)).path(id.toString()).build();
    }

    public static String getSecurityGroupTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(
                getSecurityGroups(baseUri));
    }

    // Security Group Rules
    public static URI getSecurityGroupRules(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri)).path(
                SECURITY_GROUPS_RULES).build();
    }

    public static URI getSecurityGroupRule(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
                getSecurityGroupRules(baseUri)).path(id.toString()).build();
    }

    public static String getSecurityGroupRuleTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(
                getSecurityGroupRules(baseUri));
    }
}
