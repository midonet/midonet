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
package org.midonet.api.neutron;


import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import org.midonet.api.ResourceUriBuilder;

public class NeutronUriBuilder {

    public static final String NEUTRON = "/neutron";
    public final static String NETWORKS = "/networks";
    public final static String SUBNETS = "/subnets";
    public final static String PORTS = "/ports";
    public final static String ROUTERS = "/routers";
    public final static String ADD_ROUTER_INTF = "/add_router_interface";
    public final static String REMOVE_ROUTER_INTF = "/remove_router_interface";
    public final static String FLOATING_IPS = "/floating_ips";
    public final static String SECURITY_GROUPS = "/security_groups";
    public final static String SECURITY_GROUP_RULES = "/security_group_rules";

    public static URI getNeutron(URI baseUri) {
        return UriBuilder.fromUri(ResourceUriBuilder.getRoot(baseUri)).path(
            NEUTRON).build();
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

    // Routers
    public static URI getRouters(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri)).path(ROUTERS).build();
    }

    public static URI getRouter(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getRouters(baseUri)).path(id.toString()).build();
    }

    public static String getRouterTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(getRouters(baseUri));
    }

    public static String getAddRouterInterfaceTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(getRouters(baseUri)) +
               ADD_ROUTER_INTF;
    }

    public static String getRemoveRouterInterfaceTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(getRouters(baseUri)) +
               REMOVE_ROUTER_INTF;
    }

    public static URI getFloatingIps(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri)).path(
            FLOATING_IPS).build();
    }

    public static URI getFloatingIp(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getFloatingIps(baseUri)).path(id.toString()).build();
    }

    public static String getFloatingIpTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(getFloatingIps(baseUri));
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
            SECURITY_GROUP_RULES).build();
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
