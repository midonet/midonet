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
package org.midonet.cluster.rest_api.neutron;


import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

public class NeutronUriBuilder {

    public static final String NEUTRON = "neutron";
    public final static String NETWORKS = "/networks";
    public final static String SUBNETS = "/subnets";
    public final static String PORTS = "/ports";
    public final static String ROUTERS = "/routers";
    public final static String ADD_ROUTER_INTF = "/add_router_interface";
    public final static String REMOVE_ROUTER_INTF = "/remove_router_interface";
    public final static String FLOATING_IPS = "/floating_ips";
    public final static String SECURITY_GROUPS = "/security_groups";
    public final static String SECURITY_GROUP_RULES = "/security_group_rules";

    public static final String LB = "/lb";
    public static final String VIPS = "/vips";
    public static final String POOLS = "/pools";
    public static final String MEMBERS = "/members";
    public static final String HEALTH_MONITORS = "/health_monitors";
    public static final String POOL_HEALTH_MONITOR = "/pool_health_monitor";

    public static final String FIREWALLS = "/firewalls";


    public static URI getRoot(URI baseUri) {
        return UriBuilder.fromUri(baseUri).path("/").build();
    }

    public static String buildIdTemplateUri(URI uri) {
        return uri.toString() + "/{id}";
    }

    public static URI getNeutron(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(
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
        return buildIdTemplateUri(getNetworks(baseUri));
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
        return buildIdTemplateUri(getSubnets(baseUri));
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
        return buildIdTemplateUri(getPorts(baseUri));
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
        return buildIdTemplateUri(getRouters(baseUri));
    }

    public static String getAddRouterInterfaceTemplate(URI baseUri) {
        return buildIdTemplateUri(getRouters(baseUri)) +
               ADD_ROUTER_INTF;
    }

    public static String getRemoveRouterInterfaceTemplate(URI baseUri) {
        return buildIdTemplateUri(getRouters(baseUri)) +
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
        return buildIdTemplateUri(getFloatingIps(baseUri));
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
        return buildIdTemplateUri(getSecurityGroups(baseUri));
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
        return buildIdTemplateUri(getSecurityGroupRules(baseUri));
    }

    // L4LB

    public static URI getLoadBalancer(URI baseUri) {
        return UriBuilder.fromUri(NeutronUriBuilder.getNeutron(baseUri)).path(
            LB).build();
    }

    // Vips
    public static URI getVips(URI baseUri) {
        return UriBuilder.fromUri(getLoadBalancer(baseUri)).path(VIPS).build();
    }

    public static URI getVip(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getVips(baseUri)).path(id.toString()).build();
    }

    public static String getVipTemplate(URI baseUri) {
        return buildIdTemplateUri(getVips(baseUri));
    }

    // Pools
    public static URI getPools(URI baseUri) {
        return UriBuilder.fromUri(getLoadBalancer(baseUri)).path(POOLS).build();
    }

    public static URI getPool(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getPools(baseUri)).path(id.toString()).build();
    }

    public static String getPoolTemplate(URI baseUri) {
        return buildIdTemplateUri(getPools(baseUri));
    }

    // Pool Members
    public static URI getMembers(URI baseUri) {
        return UriBuilder.fromUri(getLoadBalancer(baseUri)).path(MEMBERS)
            .build();
    }

    public static URI getMember(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getMembers(baseUri)).path(id.toString()).build();
    }

    public static String getMemberTemplate(URI baseUri) {
        return buildIdTemplateUri(getMembers(baseUri));
    }

    // Health Monitors
    public static URI getHealthMonitors(URI baseUri) {
        return UriBuilder.fromUri(getLoadBalancer(baseUri))
            .path(HEALTH_MONITORS).build();
    }

    public static URI getHealthMonitor(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getHealthMonitors(baseUri)).path(id.toString()).build();
    }

    public static String getHealthMonitorTemplate(URI baseUri) {
        return buildIdTemplateUri(getHealthMonitors(baseUri));
    }

    // Pool Health Monitor
    public static URI getPoolHealthMonitor(URI baseUri) {
        return UriBuilder.fromUri(getLoadBalancer(baseUri))
            .path(POOL_HEALTH_MONITOR).build();
    }

    // Firewalls
    public static URI getFirewalls(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
            .path(FIREWALLS).build();
    }

    public static URI getFirewall(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getFirewalls(baseUri)).path(
            id.toString()).build();
    }

    public static String getFirewallTemplate(URI baseUri) {
        return buildIdTemplateUri(getFirewalls(baseUri));
    }
}
