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
package org.midonet.brain.services.rest_api;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.ws.rs.core.UriBuilder;

import org.midonet.cluster.data.Bridge;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.IPv6Subnet;
import org.midonet.packets.MAC;

import static javax.ws.rs.core.UriBuilder.fromUri;

public class ResourceUriBuilder {

    public static final String ROOT = "/";
    public static final String TENANTS = "/tenants";
    public static final String ROUTERS = "/routers";
    public static final String BRIDGES = "/bridges";
    public static final String VLANS = "/vlans";
    public static final String MAC_TABLE = "/mac_table";
    public static final String ARP_TABLE = "/arp_table";
    public static final String DHCP = "/dhcp";
    public static final String DHCP_HOSTS = "/hosts";
    public static final String DHCPV6 = "/dhcpV6";
    public static final String DHCPV6_HOSTS = "/hostsV6";
    public static final String PORTS = "/ports";           // exterior ports
    public static final String PEER_PORTS = "/peer_ports"; // interior ports
    public static final String PORT_GROUPS = "/port_groups";
    public static final String IP_ADDR_GROUPS = "/ip_addr_groups";
    public static final String IP_ADDR = "/{ipAddr}";
    public static final String IP_ADDRS = "/ip_addrs";
    public static final String CHAINS = "/chains";
    public static final String RULES = "/rules";
    public static final String ROUTES = "/routes";
    public static final String BGP = "/bgps";
    public static final String AD_ROUTES = "/ad_routes";
    public static final String HOSTS = "/hosts";
    public static final String INTERFACES = "/interfaces";
    public static final String COMMANDS = "/commands";
    public static final String LINK = "/link";
    public static final String TUNNEL_ZONES = "/tunnel_zones";
    public static final String ID_TOKEN = "/{id}";
    public static final String SYSTEM_STATE = "/system_state";
    public static final String WRITE_VERSION = "/write_version";
    public static final String VERSIONS = "/versions";
    public static final String HEALTH_MONITORS = "/health_monitors";
    public static final String LOAD_BALANCERS = "/load_balancers";
    public static final String POOL_MEMBERS = "/pool_members";
    public static final String POOLS = "/pools";
    public static final String VIPS = "/vips";
    public static final String VLAN_ID = "/{vlanId}";
    public static final String VTEPS = "/vteps";
    public static final String VTEP_BINDINGS = "/vtep_bindings";
    public static final String BINDINGS = "/bindings";
    public static final String PORT_NAME = "/{portName}";
    public static final String VXLAN_PORT = "/vxlan_port";
    public static final String VXLAN_PORTS = "/vxlan_ports";
    public static final String MAC_ADDR = "/{macAddress}";
    public static final String PORT_ID_NO_SLASH = "{portId}";
    public static final String TENANT_ID_PARAM = "tenant_id";
    public static final String LICENSES = "/licenses";
    public static final String LICENSE_STATUS = "/licenses/status";
    public static final String TRACE_REQUESTS = "/traces";

    private ResourceUriBuilder() {
    }

    public static URI getRoot(URI baseUri) {
        return fromUri(baseUri).path(ROOT).build();
    }

    public static URI getTenants(URI baseUri) {
        return fromUri(baseUri).path(TENANTS).build();
    }

    public static URI getTenant(URI baseUri, String tenantId) {
        return fromUri(getTenants(baseUri)).path(tenantId).build();
    }

    public static String getTenantTemplate(URI baseUri) {
        return buildIdTemplateUri(getTenants(baseUri));
    }

    public static URI getTenantRouters(URI baseUri, String tenantId) {
        return fromUri(getRouters(baseUri)).queryParam(
                TENANT_ID_PARAM, tenantId).build();
    }

    public static URI getTenantBridges(URI baseUri, String tenantId) {
        return fromUri(getBridges(baseUri)).queryParam(
                TENANT_ID_PARAM, tenantId).build();
    }

    public static URI getTenantChains(URI baseUri, String tenantId) {
        return fromUri(getChains(baseUri)).queryParam(
                TENANT_ID_PARAM, tenantId).build();
    }

    public static URI getTenantPortGroups(URI baseUri, String tenantId) {
        return fromUri(getPortGroups(baseUri)).queryParam(
                TENANT_ID_PARAM, tenantId).build();
    }


    public static URI getTenantIpAddrGroups(URI baseUri, String tenantId) {
        return fromUri(getTenant(baseUri, tenantId))
                .path(IP_ADDR_GROUPS).build();
    }

    public static URI getRouters(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(ROUTERS).build();
    }

    public static URI getRouter(URI baseUri, UUID routerId) {
        return fromUri(getRouters(baseUri))
                .path(routerId.toString()).build();
    }

    public static URI getBridges(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(BRIDGES).build();
    }

    public static URI getBridge(URI baseUri, UUID bridgeId) {
        return fromUri(getBridges(baseUri))
                .path(bridgeId.toString()).build();
    }

    public static URI getPorts(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(PORTS).build();
    }

    public static URI getPort(URI baseUri, UUID portId) {
        return fromUri(getPorts(baseUri)).path(portId.toString())
                .build();
    }

    public static URI getPortLink(URI baseUri, UUID portId) {
        return fromUri(getPorts(baseUri)).path(portId.toString())
                .path(LINK).build();
    }

    public static URI getBridgePorts(URI baseUri, UUID bridgeId) {
        return fromUri(getBridge(baseUri, bridgeId)).path(PORTS)
                .build();
    }

    public static URI getBridgePeerPorts(URI baseUri, UUID bridgeId) {
        return fromUri(getBridge(baseUri, bridgeId))
                .path(PEER_PORTS).build();
    }

    public static URI getBridgeDhcps(URI baseUri, UUID bridgeId) {
        return fromUri(getBridge(baseUri, bridgeId)).path(DHCP)
                .build();
    }

    public static URI getBridgeDhcp(URI bridgeDhcpsUri, IPv4Subnet subnetAddr) {
        return fromUri(bridgeDhcpsUri).path(subnetAddr.toZkString())
                .build();
    }

    public static URI getBridgeDhcp(URI baseUri, UUID bridgeId,
                                    IPv4Subnet subnetAddr) {
        return getBridgeDhcp(getBridgeDhcps(baseUri, bridgeId), subnetAddr);
    }

    public static URI getDhcpHosts(URI bridgeDhcpUri) {
        return fromUri(bridgeDhcpUri).path(DHCP_HOSTS).build();
    }

    public static URI getMacTable(URI bridgeUri, Short vlanId) {
        UriBuilder builder = fromUri(bridgeUri);
        if (vlanId != null && vlanId != Bridge.UNTAGGED_VLAN_ID)
            builder = builder.path(VLANS).path(vlanId.toString());
        return builder.path(MAC_TABLE).build();
    }

    public static String macPortToUri(@NotNull String mac,
                                      @NotNull UUID portId) {
        return macToUri(mac) + "_" + portId.toString();
    }

    public static MAC macPortToMac(String macPortString) {
        String[] parts = macPortString.split("_");
        return macFromUri(parts[0]);
    }

    public static UUID macPortToUUID(String macPortString) {
        String[] parts = macPortString.split("_");
        return UUID.fromString(parts[1]);
    }

    public static URI getArpTable(URI bridgeUri) {
        return fromUri(bridgeUri).path(ARP_TABLE).build();
    }

    public static String ip4MacPairToUri(@NotNull String ip,
                                         @NotNull String mac) {
        return ip + "_" + mac;
    }

    public static MAC ip4MacPairToMac(String macPortString) {
        String[] parts = macPortString.split("_");
        return MAC.fromString(macStrFromUri(parts[1]));
    }

    public static IPv4Addr ip4MacPairToIP4(String macPortString) {
        String[] parts = macPortString.split("_");
        return IPv4Addr.fromString(parts[0]);
    }

    public static String macToUri(String mac) {
        return mac.replace(':', '-');
    }

    /**
     * Converts a MAC address from its URI format to standard format.
     * @param mac MAC address in URI format (e.g., 01-23-45-67-89-ab)
     * @return MAC address in standard format (e.g., 01:23:45:67:89:ab)
     */
    public static String macStrFromUri(String mac) {
        return mac.replace('-', ':');
    }

    /**
     * Converts a MAC address in URI format to a MAC object.
     * @param mac MAC address in URI format (e.g., 01-23-45-67-89-ab)
     * @return MAC object representing the address.
     */
    public static MAC macFromUri(String mac) {
        return MAC.fromString(macStrFromUri(mac));
    }

    public static URI getDhcpHost(URI bridgeDhcpUri, String macAddr) {
        return fromUri(getDhcpHosts(bridgeDhcpUri))
                .path(macToUri(macAddr)).build();
    }

    public static URI getIP4MacPair(URI bridgeUri, String ip, String mac) {
        return fromUri(getArpTable(bridgeUri))
            .path(ip4MacPairToUri(ip, mac)).build();
    }

    public static URI getMacPort(URI bridgeUri, String mac, Short vlanId,
                                 UUID portId) {
        UriBuilder builder = fromUri(bridgeUri);
        if (vlanId != null && !vlanId.equals(Bridge.UNTAGGED_VLAN_ID)) {
            builder.path(VLANS).path(vlanId.toString());
        }
        return builder.path(MAC_TABLE).path(macPortToUri(mac, portId)).build();
    }

    public static URI getBridgeDhcpV6s(URI baseUri, UUID bridgeId) {
        return fromUri(getBridge(baseUri, bridgeId)).path(DHCPV6)
                .build();
    }

    public static URI getBridgeDhcpV6(URI bridgeDhcpV6sUri, IPv6Subnet prefix) {
        return fromUri(bridgeDhcpV6sUri).path(prefix.toZkString())
                .build();
    }

    public static URI getBridgeDhcpV6(URI baseUri, UUID bridgeId,
            IPv6Subnet prefix) {
        URI dhcpV6sUri = getBridgeDhcpV6s(baseUri, bridgeId);
        return getBridgeDhcpV6(dhcpV6sUri, prefix);
    }

    public static URI getDhcpV6Hosts(URI bridgeDhcpV6Uri) {
        return fromUri(bridgeDhcpV6Uri).path(DHCPV6_HOSTS).build();
    }

    public static String clientIdToUri(String clientId) {
        return clientId.replace(':', '-');
    }

    public static String clientIdFromUri(String clientId) {
        return clientId.replace('-', ':');
    }

    public static URI getDhcpV6Host(URI bridgeDhcpV6Uri, String clientId) {
        return fromUri(getDhcpV6Hosts(bridgeDhcpV6Uri))
                .path(clientIdToUri(clientId)).build();
    }

    public static URI getRouterPorts(URI baseUri, UUID routerId) {
        return fromUri(getRouter(baseUri, routerId)).path(PORTS)
                .build();
    }

    public static URI getRouterPeerPorts(URI baseUri, UUID routerId) {
        return fromUri(getRouter(baseUri, routerId))
                .path(PEER_PORTS).build();
    }

    public static URI getChains(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(CHAINS).build();
    }

    public static URI getChain(URI baseUri, UUID chainId) {
        return fromUri(getChains(baseUri)).path(chainId.toString())
                .build();
    }

    public static URI getRules(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(RULES).build();
    }

    public static URI getRule(URI baseUri, UUID ruleId) {
        return fromUri(getRules(baseUri)).path(ruleId.toString())
                .build();
    }

    public static URI getChainRules(URI baseUri, UUID chainId) {
        return fromUri(getChain(baseUri, chainId)).path(RULES)
                .build();
    }

    public static URI getBgps(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(BGP).build();
    }

    public static URI getBgp(URI baseUri, UUID bgpId) {
        return fromUri(getBgps(baseUri)).path(bgpId.toString())
                .build();
    }

    public static URI getPortBgps(URI baseUri, UUID portId) {
        return fromUri(getPort(baseUri, portId)).path(BGP).build();
    }

    public static URI getAdRoutes(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(AD_ROUTES).build();
    }

    public static URI getAdRoute(URI baseUri, UUID adRouteId) {
        return fromUri(getAdRoutes(baseUri))
                .path(adRouteId.toString()).build();
    }

    public static URI getBgpAdRoutes(URI baseUri, UUID bgpId) {
        return fromUri(getBgp(baseUri, bgpId)).path(AD_ROUTES)
                .build();
    }

    public static URI getRoutes(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(ROUTES).build();
    }

    public static URI getRoute(URI baseUri, UUID routeId) {
        return fromUri(getRoutes(baseUri)).path(routeId.toString()).build();
    }

    public static URI getRouterRoutes(URI baseUri, UUID routerId) {
        return fromUri(getRouter(baseUri, routerId)).path(ROUTES).build();
    }

    public static URI getHosts(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(HOSTS).build();
    }

    public static URI getHost(URI baseUri, UUID hostId) {
        return fromUri(getHosts(baseUri)).path(hostId.toString()).build();
    }

    public static URI getHostInterfaces(URI baseUri, UUID hostId) {
        return fromUri(getHost(baseUri, hostId)).path(INTERFACES).build();
    }

    public static URI getHostInterface(URI baseUri, UUID hostId,
            String name) {
        return fromUri(getHostInterfaces(baseUri, hostId)).path(name).build();
    }

    public static URI getHostInterfacePorts(URI baseUri, UUID hostId) {
        return fromUri(getHost(baseUri, hostId)).path(PORTS).build();
    }

    public static URI getHostInterfacePort(URI baseUri, UUID hostId,
                                           UUID portId) {
        return fromUri(getHostInterfacePorts(baseUri, hostId)).path(
                portId.toString()).build();
    }

    public static URI getPortGroups(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(PORT_GROUPS).build();
    }

    public static URI getIpAddrGroups(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(IP_ADDR_GROUPS).build();
    }

    public static URI getPortPortGroups(URI baseUri, UUID portId) {
        return fromUri(getPort(baseUri, portId)).path(PORT_GROUPS).build();
    }

    public static URI getPortGroup(URI baseUri, UUID id) {
        return fromUri(getPortGroups(baseUri)).path(id.toString())
                .build();
    }

    public static URI getIpAddrGroup(URI baseUri, UUID id) {
        return fromUri(getIpAddrGroups(baseUri)).path(id.toString())
                .build();
    }

    public static URI getIpAddrGroupVersionAddr(URI baseUri, UUID id,
                                                int version, String addr)
            throws UnsupportedEncodingException {
        return fromUri(getIpAddrGroup(baseUri, id)).path(
                VERSIONS).path("/").path(String.valueOf(version)).path(
                IP_ADDRS).path("/").path(URLEncoder.encode(
                addr, "UTF-8")).build();
    }

    public static URI getPortGroupPorts(URI baseUri, UUID id) {
        return fromUri(getPortGroup(baseUri, id)).path(PORTS).build();
    }

    public static URI getIpAddrGroupAddrs(URI baseUri, UUID id) {
        return fromUri(getIpAddrGroup(baseUri, id)).path(IP_ADDRS).build();
    }

    public static URI getPortGroupPort(URI baseUri, UUID id, UUID portId) {
        return fromUri(getPortGroupPorts(baseUri, id))
                .path(portId.toString()).build();
    }

    public static URI getTunnelZones(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(TUNNEL_ZONES).build();
    }

    public static URI getTunnelZone(URI baseUri, UUID tunnelZoneId) {
        return fromUri(getTunnelZones(baseUri)).path(
                tunnelZoneId.toString()).build();
    }

    public static URI getTunnelZoneHosts(URI baseUri, UUID tunnelZoneId) {
        return fromUri(getTunnelZone(baseUri, tunnelZoneId
        )).path(HOSTS).build();
    }

    public static URI getTunnelZoneHost(URI baseUri, UUID tunnelZoneId,
                                        UUID hostId) {
        return fromUri(getTunnelZoneHosts(baseUri, tunnelZoneId
        )).path(hostId.toString()).build();
    }

    public static URI getVteps(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(VTEPS).build();
    }

    public static URI getVtep(URI baseUri, String ipAddr) {
        return fromUri(getVteps(baseUri)).path(ipAddr).build();
    }

    public static URI getVtepBindings(URI baseUri, String ipAddr) {
        return fromUri(getVtep(baseUri, ipAddr))
                .path(BINDINGS).build();
    }

    public static URI getVtepBinding(URI baseUri, String ipAddr,
                                     String portName, short vlanId) {
        return fromUri(getVtepBindings(baseUri, ipAddr))
                .segment(portName).segment(Short.toString(vlanId)).build();
    }

    public static URI getVtepPorts(URI baseUri, String ipAddr) {
        return fromUri(getVtep(baseUri, ipAddr))
                .path(PORTS).build();
    }

    public static URI getVxLanPortBindings(URI baseUri, UUID vxLanPortId) {
        return fromUri(getPort(baseUri, vxLanPortId))
                .path(VTEP_BINDINGS).build();
    }

    public static URI getVxLanPortBinding(URI baseUri, UUID vxLanPortId,
                                          String portName, short vlanId) {
        return fromUri(getPort(baseUri, vxLanPortId))
                .path(VTEP_BINDINGS)
                .segment(portName)
                .path(Short.toString(vlanId))
                .build();
    }

    public static String buildIdTemplateUri(URI uri) {
        return uri.toString() + ID_TOKEN;
    }

    public static String getAdRouteTemplate(URI baseUri) {
        return buildIdTemplateUri(getAdRoutes(baseUri));
    }

    public static String getBgpTemplate(URI baseUri) {
        return buildIdTemplateUri(getBgps(baseUri));
    }

    public static String getBridgeTemplate(URI baseUri) {
        return buildIdTemplateUri(getBridges(baseUri));
    }

    public static String getChainTemplate(URI baseUri) {
        return buildIdTemplateUri(getChains(baseUri));
    }

    public static String getHostTemplate(URI baseUri) {
        return buildIdTemplateUri(getHosts(baseUri));
    }

    public static String getPortTemplate(URI baseUri) {
        return buildIdTemplateUri(getPorts(baseUri));
    }

    public static String getPortGroupTemplate(URI baseUri) {
        return buildIdTemplateUri(getPortGroups(baseUri));
    }

    public static String getIpAddrGroupTemplate(URI baseUri) {
        return buildIdTemplateUri(getIpAddrGroups(baseUri));
    }

    public static String getRouteTemplate(URI baseUri) {
        return buildIdTemplateUri(getRoutes(baseUri));
    }

    public static String getRouterTemplate(URI baseUri) {
        return buildIdTemplateUri(getRouters(baseUri));
    }

    public static String getRuleTemplate(URI baseUri) {
        return buildIdTemplateUri(getRules(baseUri));
    }

    public static String getTunnelZoneTemplate(URI baseUri) {
        return buildIdTemplateUri(getTunnelZones(baseUri));
    }

    public static String getVlanMacTableTemplate(URI bridgeUri) {
        return bridgeUri + VLANS + VLAN_ID + MAC_TABLE;
    }

    public static String getMacPortTemplate(URI bridgeUri) {
        return bridgeUri + MAC_TABLE + MAC_ADDR + "_" + PORT_ID_NO_SLASH;
    }

    public static String getVlanMacPortTemplate(URI bridgeUri) {
        return bridgeUri + VLANS + VLAN_ID + MAC_TABLE +
                MAC_ADDR + "_" + PORT_ID_NO_SLASH;
    }

    public static String getVtepTemplate(URI baseUri) {
        return getVteps(baseUri) + IP_ADDR;
    }

    public static String getVtepBindingsTemplate(URI baseUri) {
        return getVtepTemplate(baseUri) + BINDINGS;
    }

    public static String getVtepBindingTemplate(URI baseUri, String ipAddr) {
        return getVtepBindings(baseUri, ipAddr).toString() +
                PORT_NAME + VLAN_ID;
    }

    public static URI getWriteVersion(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(WRITE_VERSION).build();
    }

    public static URI getSystemState(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(SYSTEM_STATE).build();
    }

    public static URI getHostVersions(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(VERSIONS).build();
    }

    public static URI getTraceRequests(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(TRACE_REQUESTS).build();
    }

    public static URI getTraceRequest(URI baseUri, UUID traceRequestId) {
        return fromUri(getTraceRequests(baseUri))
            .path(traceRequestId.toString()).build();
    }

    public static URI getHealthMonitors(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(HEALTH_MONITORS).build();
    }

    public static URI getHealthMonitor(URI baseUri, UUID healthMonitorId) {
        return fromUri(getHealthMonitors(baseUri))
                .path(healthMonitorId.toString()).build();
    }

    public static String getHealthMonitorTemplate(URI baseUri) {
        return buildIdTemplateUri(getHealthMonitors(baseUri));
    }

    public static URI getHealthMonitorPools(URI baseUri, UUID healthMonitorId) {
        return fromUri(getHealthMonitors(baseUri))
                .path(healthMonitorId.toString()).path(POOLS).build();
    }

    public static URI getLoadBalancers(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(LOAD_BALANCERS).build();
    }

    public static String getLoadBalancerTemplate(URI baseUri) {
        return buildIdTemplateUri(getLoadBalancers(baseUri));
    }

    public static URI getLoadBalancer(URI baseUri, UUID loadBalancerId) {
        return fromUri(getLoadBalancers(baseUri))
                .path(loadBalancerId.toString()).build();
    }

   public static URI getLoadBalancerPools(URI baseUri, UUID id) {
       return fromUri(getLoadBalancers(baseUri))
               .path(id.toString()).path(POOLS).build();
   }

    public static URI getLoadBalancerVips(URI baseUri, UUID id) {
        return fromUri(getLoadBalancers(baseUri))
                .path(id.toString()).path(VIPS).build();
    }

    public static URI getPoolMembers(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(POOL_MEMBERS).build();
    }

    public static URI getPoolMember(URI baseUri, UUID poolMemberId) {
        return fromUri(getPoolMembers(baseUri))
                .path(poolMemberId.toString()).build();
    }

    public static String getPoolMemberTemplate(URI baseUri) {
        return buildIdTemplateUri(getPoolMembers(baseUri));
    }

    public static URI getPools(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(POOLS).build();
    }

    public static URI getPool(URI baseUri, UUID poolId) {
        return fromUri(getPools(baseUri)).path(poolId.toString()).build();
    }

    public static String getPoolTemplate(URI baseUri) {
        return buildIdTemplateUri(getPools(baseUri));
    }

    public static URI getPoolPoolMembers(URI baseUri, UUID poolId) {
        return fromUri(getPools(baseUri)).path(poolId.toString())
                                         .path(POOL_MEMBERS)
                                         .build();
    }

    public static URI getPoolVips(URI baseUri, UUID poolId) {
        return fromUri(getPools(baseUri)).path(poolId.toString())
                                         .path(VIPS)
                                         .build();
    }

    public static URI getVips(URI baseUri) {
        return fromUri(getRoot(baseUri)).path(VIPS).build();
    }

    public static String getVipTemplate(URI baseUri) {
        return buildIdTemplateUri(getVips(baseUri));
    }

    public static URI getVip(URI baseUri, UUID vipId) {
        return fromUri(getVips(baseUri)).path(vipId.toString()).build();
    }
}
