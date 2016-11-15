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

    @JsonProperty("load_balancers_v2")
    public URI loadBalancersV2;

    @JsonProperty("pools_v2")
    public URI poolsV2;

    @JsonProperty("pool_members_v2")
    public URI poolMembersV2;

    @JsonProperty("listeners_v2")
    public URI listenersV2;

    @JsonProperty("health_monitors_v2")
    public URI healthMonitorsV2;

    @JsonProperty("firewalls")
    public URI firewalls;

    @JsonProperty("firewall_template")
    public String firewallTemplate;

    @JsonProperty("vpn_services")
    public URI vpnServices;

    @JsonProperty("vpn_service_template")
    public String vpnServiceTemplate;

    @JsonProperty("ipsec_site_conns")
    public URI ipsecSiteConnections;

    @JsonProperty("ipsec_site_conn_template")
    public String ipsecSiteConnectionTemplate;

    @JsonProperty("l2_gateway_connections")
    public URI l2GatewayConns;

    @JsonProperty("l2_gateway_connection_template")
    public String l2GatewayConnTemplate;

    @JsonProperty("gateway_devices")
    public URI gatewayDevices;

    @JsonProperty("gateway_device_template")
    public String gatewayDeviceTemplate;

    @JsonProperty("remote_mac_entries")
    public URI remoteMacEntries;

    @JsonProperty("remote_mac_entry_template")
    public String remoteMacEntryTemplate;

    @JsonProperty("bgp_speakers")
    public URI bgpSpeakers;

    @JsonProperty("bgp_speaker_template")
    public String bgpSpeakerTemplate;

    @JsonProperty("bgp_peers")
    public URI bgpPeers;

    @JsonProperty("bgp_peer_template")
    public String bgpPeerTemplate;

    @JsonProperty("tap_flows")
    public URI tapFlows;

    @JsonProperty("tap_flow_template")
    public String tapFlowTemplate;

    @JsonProperty("tap_services")
    public URI tapServices;

    @JsonProperty("tap_service_template")
    public String tapServiceTemplate;

    @JsonProperty("firewall_logs")
    public URI firewallLogs;

    @JsonProperty("firewall_log_template")
    public String firewallLogTemplate;

    @JsonProperty("logging_resources")
    public URI loggingResources;

    @JsonProperty("logging_resource_template")
    public String loggingResourceTemplate;

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
               && Objects.equal(loadBalancersV2, other.loadBalancersV2)
               && Objects.equal(poolsV2, other.poolsV2)
               && Objects.equal(poolMembersV2, other.poolMembersV2)
               && Objects.equal(listenersV2, other.listenersV2)
               && Objects.equal(healthMonitorsV2, other.healthMonitorsV2)
               && Objects.equal(firewalls, other.firewalls)
               && Objects.equal(firewallTemplate, other.firewallTemplate)
               && Objects.equal(vpnServices, other.vpnServices)
               && Objects.equal(vpnServiceTemplate, other.vpnServiceTemplate)
               && Objects.equal(ipsecSiteConnections,
                                other.ipsecSiteConnections)
               && Objects.equal(ipsecSiteConnectionTemplate,
                                other.ipsecSiteConnectionTemplate)
               && Objects.equal(l2GatewayConns, other.l2GatewayConns)
               && Objects.equal(l2GatewayConnTemplate, other.l2GatewayConnTemplate)
               && Objects.equal(gatewayDevices, other.gatewayDevices)
               && Objects.equal(gatewayDeviceTemplate, other.gatewayDeviceTemplate)
               && Objects.equal(remoteMacEntries, other.remoteMacEntries)
               && Objects.equal(remoteMacEntryTemplate, other.remoteMacEntryTemplate)
               && Objects.equal(bgpSpeakers, other.bgpSpeakers)
               && Objects.equal(bgpSpeakerTemplate, other.bgpSpeakerTemplate)
               && Objects.equal(bgpPeers, other.bgpPeers)
               && Objects.equal(bgpPeerTemplate, other.bgpPeerTemplate)
               && Objects.equal(tapFlows, other.tapFlows)
               && Objects.equal(tapFlowTemplate, other.tapFlowTemplate)
               && Objects.equal(tapServices, other.tapServices)
               && Objects.equal(tapServiceTemplate, other.tapServiceTemplate)
               && Objects.equal(firewallLogs, other.firewallLogs)
               && Objects.equal(firewallLogTemplate, other.firewallLogTemplate)
               && Objects.equal(loggingResources, other.loggingResources)
               && Objects.equal(loggingResourceTemplate, other.loggingResourceTemplate);
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
                                loadBalancer, loadBalancersV2, poolsV2,
                                poolMembersV2, listenersV2, healthMonitorsV2,
                                firewallTemplate,
                                vpnServices, vpnServiceTemplate,
                                ipsecSiteConnections,
                                ipsecSiteConnectionTemplate,
                                l2GatewayConns, l2GatewayConnTemplate,
                                gatewayDevices, gatewayDeviceTemplate,
                                remoteMacEntries, remoteMacEntryTemplate,
                                bgpSpeakers, bgpSpeakerTemplate,
                                bgpPeers, bgpPeerTemplate,
                                tapFlows, tapFlowTemplate,
                                tapServices, tapServiceTemplate,
                                firewallLogs, firewallLogTemplate,
                                loggingResources, loggingResourceTemplate);
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
            .add("loadBalancersV2", loadBalancersV2)
            .add("poolsV2", poolsV2)
            .add("poolMembersV2", poolMembersV2)
            .add("listenersV2", listenersV2)
            .add("healthMonitorsV2", healthMonitorsV2)
            .add("firewalls", firewalls)
            .add("firewallTemplate", firewallTemplate)
            .add("vpnServices", vpnServices)
            .add("vpnServiceTemplate", vpnServiceTemplate)
            .add("ipsecSiteConnections", ipsecSiteConnections)
            .add("ipsecSiteConnectionTemplate", ipsecSiteConnectionTemplate)
            .add("l2GatewayConns", l2GatewayConns)
            .add("l2GatewayConnTemplate", l2GatewayConnTemplate)
            .add("remoteMacEntries", remoteMacEntries)
            .add("remoteMacEntryTemplate", remoteMacEntryTemplate)
            .add("bgpSpeakers", bgpSpeakers)
            .add("bgpSpeakerTemplate", bgpSpeakerTemplate)
            .add("bgpPeers", bgpPeers)
            .add("bgpPeerTemplate", bgpPeerTemplate)
            .add("tapFlows", tapFlows)
            .add("tapFlowTemplate", tapFlowTemplate)
            .add("tapServices", tapServices)
            .add("tapServiceTemplate", tapServiceTemplate)
            .add("firewallLogs", firewallLogs)
            .add("firewallLogTemplate", firewallLogTemplate)
            .add("loggingResources", loggingResources)
            .add("loggingResourceTemplate", loggingResourceTemplate)
            .toString();
    }
}
