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

    public static final String LBS_V2 = "/lb_v2";
    public static final String LB_ISTENERS_V2 = "/listeners_v2";
    public static final String POOLS_V2 = "/pools_v2";
    public static final String POOL_MEMBERS_V2 = "/pool_members_v2";
    public static final String HEALTH_MONITORS_V2 = "/health_monitors_v2";

    public static final String FIREWALLS = "/firewalls";
    public static final String VPNSERVICES = "/vpnservices";
    public static final String IPSEC_SITE_CONNECTIONS =
        "/ipsec_site_connections";
    public static final String L2_GATEWAY_CONNS= "/l2_gateway_connections";
    public static final String GATEWAY_DEVICES = "/gateway_devices";
    public static final String REMOTE_MAC_ENTRIES = "/remote_mac_entries";
    public static final String BGP_SPEAKERS = "/bgp_speakers";
    public static final String BGP_PEERS = "/bgp_peers";
    public static final String TAP_SERVICES = "/tap_services";
    public static final String TAP_FLOWS = "/tap_flows";
    public static final String FIREWALL_LOGS = "/firewall_logs";
    public static final String LOGGING_RESOURCES = "/logging_resources";


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

    // LB V2
    public static URI getLoadBalancersV2(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri)).path(LBS_V2).build();
    }

    public static URI getLoadBalancerV2(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getLoadBalancersV2(baseUri)).path(id.toString()).build();
    }

    public static String getLoadBalancerV2Template(URI baseUri) {
        return buildIdTemplateUri(getLoadBalancersV2(baseUri));
    }

    // Listeners (V2)
    public static URI getListenersV2(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri)).path(LB_ISTENERS_V2).build();
    }

    public static URI getListenerV2(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getListenersV2(baseUri)).path(id.toString()).build();
    }

    public static String getListenerV2Template(URI baseUri) {
        return buildIdTemplateUri(getListenersV2(baseUri));
    }

    // Pools V2
    public static URI getPoolsV2(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri)).path(POOLS_V2).build();
    }

    public static URI getPoolV2(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getPoolsV2(baseUri)).path(id.toString()).build();
    }

    public static String getPoolV2Template(URI baseUri) {
        return buildIdTemplateUri(getPoolsV2(baseUri));
    }

    // Pool Members V2
    public static URI getPoolMembersV2(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri)).path(POOL_MEMBERS_V2)
            .build();
    }

    public static URI getPoolMemberV2(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getPoolMembersV2(baseUri)).path(id.toString()).build();
    }

    public static String getPoolMemberV2Template(URI baseUri) {
        return buildIdTemplateUri(getPoolMembersV2(baseUri));
    }

    // Health Monitors V2
    public static URI getHealthMonitorsV2(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
            .path(HEALTH_MONITORS_V2).build();
    }

    public static URI getHealthMonitorV2(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getHealthMonitorsV2(baseUri)).path(id.toString()).build();
    }

    public static String getHealthMonitorV2Template(URI baseUri) {
        return buildIdTemplateUri(getHealthMonitorsV2(baseUri));
    }

    // Neutron BGP
    public static URI getBgpSpeakers(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
            .path(BGP_SPEAKERS).build();
    }

    public static URI getBgpSpeaker(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getBgpSpeakers(baseUri)).path(
            id.toString()).build();
    }

    public static String getBgpSpeakerTemplate(URI baseUri) {
        return buildIdTemplateUri(getBgpSpeakers(baseUri));
    }

    public static URI getBgpPeers(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
                .path(BGP_PEERS).build();
    }

    public static URI getBgpPeer(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getBgpPeers(baseUri)).path(
                id.toString()).build();
    }

    public static String getBgpPeerTemplate(URI baseUri) {
        return buildIdTemplateUri(getBgpPeers(baseUri));
    }

    // Firewall Logging
    public static URI getFirewallLogs(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
            .path(FIREWALL_LOGS).build();
    }

    public static URI getFirewallLog(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getFirewallLogs(baseUri)).path(
                id.toString()).build();
    }

    public static String getFirewallLogTemplate(URI baseUri) {
        return buildIdTemplateUri(getFirewallLogs(baseUri));
    }

    public static URI getLoggingResources(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
                .path(LOGGING_RESOURCES).build();
    }

    public static URI getLoggingResource(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getLoggingResources(baseUri)).path(
                id.toString()).build();
    }

    public static String getLoggingResourceTemplate(URI baseUri) {
        return buildIdTemplateUri(getLoggingResources(baseUri));
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

    public static URI getVpnServices(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
            .path(VPNSERVICES).build();
    }

    public static URI getVpnService(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getVpnServices(baseUri))
            .path(id.toString()).build();
    }

    public static String getVpnServiceTemplate(URI baseUri) {
        return buildIdTemplateUri(getVpnServices(baseUri));
    }

    public static URI getIpsecSiteConnections(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
            .path(IPSEC_SITE_CONNECTIONS).build();
    }

    public static URI getIpsecSiteConnection(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getIpsecSiteConnections(baseUri))
            .path(id.toString()).build();
    }

    public static String getIpsecSiteConnectionTemplate(URI baseUri) {
        return buildIdTemplateUri(getIpsecSiteConnections(baseUri));
    }

    public static URI getL2GatewayConns(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
            .path(L2_GATEWAY_CONNS).build();
    }

    public static URI getL2GatewayConn(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getL2GatewayConns(baseUri))
            .path(id.toString()).build();
    }

    public static String getL2GatewayConnTemplate(URI baseUri) {
        return buildIdTemplateUri(getL2GatewayConns(baseUri));
    }

    public static URI getGatewayDevices(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
                .path(GATEWAY_DEVICES).build();
    }

    public static URI getGatewayDevice(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getGatewayDevices(baseUri))
                .path(id.toString()).build();
    }

    public static String getGatewayDeviceTemplate(URI baseUri) {
        return buildIdTemplateUri(getGatewayDevices(baseUri));
    }

    public static URI getRemoteMacEntries(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
                .path(REMOTE_MAC_ENTRIES).build();
    }

    public static URI getRemoteMacEntry(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getRemoteMacEntries(baseUri))
                .path(id.toString()).build();
    }

    public static String getRemoteMacEntryTemplate(URI baseUri) {
        return buildIdTemplateUri(getRemoteMacEntries(baseUri));
    }

    // Tap Services
    public static URI getTapServices(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
                .path(TAP_SERVICES).build();
    }

    public static URI getTapService(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getTapServices(baseUri))
                .path(id.toString()).build();
    }

    public static String getTapServiceTemplate(URI baseUri) {
        return buildIdTemplateUri(getTapServices(baseUri));
    }

    // Tap Flows
    public static URI getTapFlows(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri))
                .path(TAP_FLOWS).build();
    }

    public static URI getTapFlow(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getTapFlows(baseUri))
                .path(id.toString()).build();
    }

    public static String getTapFlowTemplate(URI baseUri) {
        return buildIdTemplateUri(getTapFlows(baseUri));
    }
}
