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
package org.midonet.api;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import org.midonet.api.network.IP4MacPair;
import org.midonet.api.network.MacPort;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.IPv6Subnet;
import org.midonet.packets.MAC;

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
    public static final String TENANT_ID_PARAM = "tenant_id";
    public static final String TRACE_REQUESTS = "/traces";

    private ResourceUriBuilder() {
    }

    public static URI getRoot(URI baseUri) {
        return UriBuilder.fromUri(baseUri).path(ROOT).build();
    }

    public static URI getTenants(URI baseUri) {
        return UriBuilder.fromUri(baseUri).path(TENANTS).build();
    }

    public static URI getTenant(URI baseUri, String tenantId) {
        return UriBuilder.fromUri(getTenants(baseUri)).path(tenantId).build();
    }

    public static String getTenantTemplate(URI baseUri) {
        return buildIdTemplateUri(getTenants(baseUri));
    }

    public static URI getTenantRouters(URI baseUri, String tenantId) {
        return UriBuilder.fromUri(getRouters(baseUri)).queryParam(
                TENANT_ID_PARAM, tenantId).build();
    }

    public static URI getTenantBridges(URI baseUri, String tenantId) {
        return UriBuilder.fromUri(getBridges(baseUri)).queryParam(
            TENANT_ID_PARAM, tenantId).build();
    }

    public static URI getTenantChains(URI baseUri, String tenantId) {
        return UriBuilder.fromUri(getChains(baseUri)).queryParam(
            TENANT_ID_PARAM, tenantId).build();
    }

    public static URI getTenantPortGroups(URI baseUri, String tenantId) {
        return UriBuilder.fromUri(getPortGroups(baseUri)).queryParam(
            TENANT_ID_PARAM, tenantId).build();
    }

    public static URI getRouters(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(ROUTERS).build();
    }

    public static URI getRouter(URI baseUri, UUID routerId) {
        return UriBuilder.fromUri(getRouters(baseUri))
                .path(routerId.toString()).build();
    }

    public static URI getBridges(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(BRIDGES).build();
    }

    public static URI getBridge(URI baseUri, UUID bridgeId) {
        return UriBuilder.fromUri(getBridges(baseUri))
                .path(bridgeId.toString()).build();
    }

    public static URI getPorts(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(PORTS).build();
    }

    public static URI getPort(URI baseUri, UUID portId) {
        return UriBuilder.fromUri(getPorts(baseUri)).path(portId.toString())
                .build();
    }

    public static URI getPortLink(URI baseUri, UUID portId) {
        return UriBuilder.fromUri(getPorts(baseUri)).path(portId.toString())
                .path(LINK).build();
    }

    public static URI getBridgeDhcps(URI baseUri, UUID bridgeId) {
        return UriBuilder.fromUri(getBridge(baseUri, bridgeId)).path(DHCP)
                .build();
    }

    public static URI getBridgeDhcp(URI bridgeDhcpsUri, IPv4Subnet subnetAddr) {
        return UriBuilder.fromUri(bridgeDhcpsUri).path(subnetAddr.toZkString())
                .build();
    }

    public static URI getBridgeDhcp(URI baseUri, UUID bridgeId,
                                    IPv4Subnet subnetAddr) {
        return getBridgeDhcp(getBridgeDhcps(baseUri, bridgeId), subnetAddr);
    }

    public static URI getDhcpHosts(URI bridgeDhcpUri) {
        return UriBuilder.fromUri(bridgeDhcpUri).path(DHCP_HOSTS).build();
    }

    public static URI getMacTable(URI bridgeUri, Short vlanId) {
        UriBuilder builder = UriBuilder.fromUri(bridgeUri);
        if (vlanId != null && vlanId != Bridge.UNTAGGED_VLAN_ID)
            builder = builder.path(VLANS).path(vlanId.toString());
        return builder.path(MAC_TABLE).build();
    }

    public static String macPortToUri(MacPort mp) {
        return macToUri(mp.getMacAddr()) + "_" + mp.getPortId().toString();
    }

    public static URI getArpTable(URI bridgeUri) {
        return UriBuilder.fromUri(bridgeUri).path(ARP_TABLE).build();
    }

    public static String ip4MacPairToUri(IP4MacPair pair) {
        return pair.getIp() + "_" + macToUri(pair.getMac());
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
        return ResourceUris.macToUri(mac);
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
        return UriBuilder.fromUri(getDhcpHosts(bridgeDhcpUri))
                .path(macToUri(macAddr)).build();
    }

    public static URI getIP4MacPair(URI bridgeUri, IP4MacPair pair) {
        return UriBuilder.fromUri(getArpTable(bridgeUri))
            .path(ip4MacPairToUri(pair)).build();
    }

    public static URI getMacPort(URI bridgeUri, Short vlanId,
                                 String macAddress, UUID portId) {
        return getMacPort(bridgeUri, new MacPort(vlanId, macAddress, portId));
    }

    public static URI getMacPort(URI bridgeUri, MacPort mp) {
        UriBuilder builder = UriBuilder.fromUri(bridgeUri);
        if (mp.getVlanId() != null &&
                !mp.getVlanId().equals(Bridge.UNTAGGED_VLAN_ID)) {
            builder.path(VLANS).path(mp.getVlanId().toString());
        }
        return builder.path(MAC_TABLE).path(macPortToUri(mp)).build();
    }

    public static URI getBridgeDhcpV6s(URI baseUri, UUID bridgeId) {
        return UriBuilder.fromUri(getBridge(baseUri, bridgeId)).path(DHCPV6)
                .build();
    }

    public static URI getBridgeDhcpV6(URI bridgeDhcpV6sUri, IPv6Subnet prefix) {
        return UriBuilder.fromUri(bridgeDhcpV6sUri).path(prefix.toZkString())
                .build();
    }

    public static URI getBridgeDhcpV6(URI baseUri, UUID bridgeId,
            IPv6Subnet prefix) {
        URI dhcpV6sUri = getBridgeDhcpV6s(baseUri, bridgeId);
        return getBridgeDhcpV6(dhcpV6sUri, prefix);
    }

    public static URI getDhcpV6Hosts(URI bridgeDhcpV6Uri) {
        return UriBuilder.fromUri(bridgeDhcpV6Uri).path(DHCPV6_HOSTS).build();
    }

    public static String clientIdToUri(String clientId) {
        return clientId.replace(':', '-');
    }

    public static String clientIdFromUri(String clientId) {
        return clientId.replace('-', ':');
    }

    public static URI getDhcpV6Host(URI bridgeDhcpV6Uri, String clientId) {
        return UriBuilder.fromUri(getDhcpV6Hosts(bridgeDhcpV6Uri))
                .path(clientIdToUri(clientId)).build();
    }

    public static URI getChains(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(CHAINS).build();
    }

    public static URI getChain(URI baseUri, UUID chainId) {
        return UriBuilder.fromUri(getChains(baseUri)).path(chainId.toString())
                .build();
    }

    public static URI getRules(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(RULES).build();
    }

    public static URI getRule(URI baseUri, UUID ruleId) {
        return UriBuilder.fromUri(getRules(baseUri)).path(ruleId.toString())
                .build();
    }

    public static URI getBgps(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(BGP).build();
    }

    public static URI getBgp(URI baseUri, UUID bgpId) {
        return UriBuilder.fromUri(getBgps(baseUri)).path(bgpId.toString())
                .build();
    }

    public static URI getPortBgps(URI baseUri, UUID portId) {
        return UriBuilder.fromUri(getPort(baseUri, portId)).path(BGP).build();
    }

    public static URI getAdRoutes(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(AD_ROUTES).build();
    }

    public static URI getAdRoute(URI baseUri, UUID adRouteId) {
        return UriBuilder.fromUri(getAdRoutes(baseUri))
                .path(adRouteId.toString()).build();
    }

    public static URI getBgpAdRoutes(URI baseUri, UUID bgpId) {
        return UriBuilder.fromUri(getBgp(baseUri, bgpId)).path(AD_ROUTES)
                .build();
    }

    public static URI getRoutes(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(ROUTES).build();
    }

    public static URI getRoute(URI baseUri, UUID routeId) {
        return UriBuilder.fromUri(getRoutes(baseUri)).path(routeId.toString())
                .build();
    }

    public static URI getHosts(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(HOSTS).build();
    }

    public static URI getHost(URI baseUri, UUID hostId) {
        return UriBuilder.fromUri(getHosts(baseUri)).path(hostId.toString())
                .build();
    }

    public static URI getHostInterfacePorts(URI baseUri, UUID hostId) {
        return UriBuilder.fromUri(getHost(baseUri, hostId)).path(PORTS).build();
    }

    public static URI getHostInterfacePort(URI baseUri, UUID hostId,
                                           UUID portId) {
        return UriBuilder.fromUri(getHostInterfacePorts(baseUri, hostId)).path(
                portId.toString()).build();
    }

    public static URI getPortGroups(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(PORT_GROUPS).build();
    }

    public static URI getIpAddrGroups(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(
                IP_ADDR_GROUPS).build();
    }

    public static URI getPortPortGroups(URI baseUri, UUID portId) {
        return UriBuilder.fromUri(getPort(baseUri, portId)).path(
                PORT_GROUPS).build();
    }

    public static URI getPortGroup(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getPortGroups(baseUri)).path(id.toString())
                .build();
    }

    public static URI getIpAddrGroup(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getIpAddrGroups(baseUri)).path(id.toString())
                .build();
    }

    public static URI getIpAddrGroupVersionAddr(URI baseUri, UUID id,
                                                int version, String addr)
            throws UnsupportedEncodingException {
        return UriBuilder.fromUri(getIpAddrGroup(baseUri, id)).path(
                VERSIONS).path("/").path(String.valueOf(version)).path(
                IP_ADDRS).path("/").path(URLEncoder.encode(
                addr, "UTF-8")).build();
    }

    public static URI getPortGroupPorts(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getPortGroup(baseUri, id))
                .path(PORTS).build();
    }

    public static URI getIpAddrGroupAddrs(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getIpAddrGroup(baseUri, id))
                .path(IP_ADDRS).build();
    }

    public static URI getPortGroupPort(URI baseUri, UUID id, UUID portId) {
        return UriBuilder.fromUri(getPortGroupPorts(baseUri, id))
                .path(portId.toString()).build();
    }

    public static URI getTunnelZones(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(TUNNEL_ZONES).build();
    }

    public static URI getTunnelZone(URI baseUri, UUID tunnelZoneId) {
        return UriBuilder.fromUri(getTunnelZones(baseUri)).path(
                tunnelZoneId.toString()).build();
    }

    public static URI getTunnelZoneHosts(URI baseUri, UUID tunnelZoneId) {
        return UriBuilder.fromUri(getTunnelZone(baseUri, tunnelZoneId
        )).path(HOSTS).build();
    }

    public static URI getTunnelZoneHost(URI baseUri, UUID tunnelZoneId,
                                        UUID hostId) {
        return UriBuilder.fromUri(getTunnelZoneHosts(baseUri, tunnelZoneId
        )).path(hostId.toString()).build();
    }

    public static URI getVteps(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(VTEPS).build();
    }

    public static URI getVtep(URI baseUri, String ipAddr) {
        return UriBuilder.fromUri(getVteps(baseUri)).path(ipAddr).build();
    }

    public static URI getVtepBindings(URI baseUri, String ipAddr) {
        return UriBuilder.fromUri(getVtep(baseUri, ipAddr))
                .path(BINDINGS).build();
    }

    public static URI getVtepBinding(URI baseUri, String ipAddr,
                                     String portName, short vlanId) {
        return UriBuilder.fromUri(getVtepBindings(baseUri, ipAddr))
                .segment(portName).segment(Short.toString(vlanId)).build();
    }

    public static URI getVtepPorts(URI baseUri, String ipAddr) {
        return UriBuilder.fromUri(getVtep(baseUri, ipAddr))
                .path(PORTS).build();
    }

    public static URI getVxLanPortBindings(URI baseUri, UUID vxLanPortId) {
        return UriBuilder.fromUri(getPort(baseUri, vxLanPortId))
                .path(VTEP_BINDINGS).build();
    }

    public static URI getVxLanPortBinding(URI baseUri, UUID vxLanPortId,
                                          String portName, short vlanId) {
        return UriBuilder.fromUri(getPort(baseUri, vxLanPortId))
                .path(VTEP_BINDINGS)
                .segment(portName)
                .path(Short.toString(vlanId))
                .build();
    }

    public static String buildIdTemplateUri(URI uri) {
        return uri.toString() + ID_TOKEN;
    }

    /**
     * Generate an ad route URI template
     *
     * @param baseUri Base URI
     * @return Ad route template URI
     */
    public static String getAdRouteTemplate(URI baseUri) {
        return buildIdTemplateUri(getAdRoutes(baseUri));
    }

    /**
     * Generate a BGP URI template
     *
     * @param baseUri Base URI
     * @return BGP template URI
     */
    public static String getBgpTemplate(URI baseUri) {
        return buildIdTemplateUri(getBgps(baseUri));
    }

    /**
     * Generate a bridge URI template
     *
     * @param baseUri Base URI
     * @return Bridge template URI
     */
    public static String getBridgeTemplate(URI baseUri) {
        return buildIdTemplateUri(getBridges(baseUri));
    }

    /**
     * Generate a chain URI template
     *
     * @param baseUri Base URI
     * @return Chain template URI
     */
    public static String getChainTemplate(URI baseUri) {
        return buildIdTemplateUri(getChains(baseUri));
    }

    /**
     * Generate a host URI template
     *
     * @param baseUri Base URI
     * @return Host template URI
     */
    public static String getHostTemplate(URI baseUri) {
        return buildIdTemplateUri(getHosts(baseUri));
    }

    /**
     * Generate a port URI template
     *
     * @param baseUri Base URI
     * @return Porttemplate URI
     */
    public static String getPortTemplate(URI baseUri) {
        return buildIdTemplateUri(getPorts(baseUri));
    }

    /**
     * Generate a port group URI template
     *
     * @param baseUri Base URI
     * @return Port group template URI
     */
    public static String getPortGroupTemplate(URI baseUri) {
        return buildIdTemplateUri(getPortGroups(baseUri));
    }

    /**
     * Generate a IP address group URI template
     *
     * @param baseUri Base URI
     * @return IP address group template URI
     */
    public static String getIpAddrGroupTemplate(URI baseUri) {
        return buildIdTemplateUri(getIpAddrGroups(baseUri));
    }

    /**
     * Generate a route URI template
     *
     * @param baseUri Base URI
     * @return Route template URI
     */
    public static String getRouteTemplate(URI baseUri) {
        return buildIdTemplateUri(getRoutes(baseUri));
    }

    /**
     * Generate a router URI template
     *
     * @param baseUri Base URI
     * @return Router template URI
     */
    public static String getRouterTemplate(URI baseUri) {
        return buildIdTemplateUri(getRouters(baseUri));
    }

    /**
     * Generate a rule URI template
     *
     * @param baseUri Base URI
     * @return Rule template URI
     */
    public static String getRuleTemplate(URI baseUri) {
        return buildIdTemplateUri(getRules(baseUri));
    }

    /**
     * Generate a tunnel zone URI template
     *
     * @param baseUri Base URI
     * @return Tunnel zone template URI
     */
    public static String getTunnelZoneTemplate(URI baseUri) {
        return buildIdTemplateUri(getTunnelZones(baseUri));
    }

    public static String getVtepTemplate(URI baseUri) {
        return getVteps(baseUri) + IP_ADDR;
    }

    public static String getVtepBindingTemplate(URI baseUri, String ipAddr) {
        return getVtepBindings(baseUri, ipAddr).toString() +
                PORT_NAME + VLAN_ID;
    }

    /**
     * Generate the Write Version URI.
     *
     * @param baseUri Base Service URI
     * @return Write Version URI
     */
    public static URI getWriteVersion(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(WRITE_VERSION).build();
    }

    /**
     * Generate the System State URI
     *
     * @param baseUri Base Service URI
     * @return System State URI
     */
    public static URI getSystemState(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(SYSTEM_STATE).build();
    }

    /**
     * Generate the Host Versions URI
     *
     * @param baseUri Base Service URI
     * @return System State URI
     */
    public static URI getHostVersions(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(VERSIONS).build();
    }

    public static URI getTraceRequests(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri))
            .path(TRACE_REQUESTS).build();
    }

    public static URI getTraceRequest(URI baseUri, UUID traceRequestId) {
        return UriBuilder.fromUri(getTraceRequests(baseUri))
            .path(traceRequestId.toString()).build();
    }

    public static URI getHealthMonitors(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri))
                .path(HEALTH_MONITORS).build();
    }

    public static URI getHealthMonitor(URI baseUri, UUID healthMonitorId) {
        return UriBuilder.fromUri(getHealthMonitors(baseUri))
                .path(healthMonitorId.toString()).build();
    }

    public static String getHealthMonitorTemplate(URI baseUri) {
        return buildIdTemplateUri(getHealthMonitors(baseUri));
    }

    public static URI getLoadBalancers(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri))
                .path(LOAD_BALANCERS).build();
    }

    public static String getLoadBalancerTemplate(URI baseUri) {
        return buildIdTemplateUri(getLoadBalancers(baseUri));
    }

    public static URI getLoadBalancer(URI baseUri, UUID loadBalancerId) {
        return UriBuilder.fromUri(getLoadBalancers(baseUri))
                .path(loadBalancerId.toString()).build();
    }

    public static URI getPoolMembers(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(POOL_MEMBERS).build();
    }

    public static URI getPoolMember(URI baseUri, UUID poolMemberId) {
        return UriBuilder.fromUri(getPoolMembers(baseUri))
                .path(poolMemberId.toString()).build();
    }

    public static String getPoolMemberTemplate(URI baseUri) {
        return buildIdTemplateUri(getPoolMembers(baseUri));
    }

    public static URI getPools(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(POOLS).build();
    }

    public static URI getPool(URI baseUri, UUID poolId) {
        return UriBuilder.fromUri(getPools(baseUri))
                .path(poolId.toString()).build();
    }

    public static String getPoolTemplate(URI baseUri) {
        return buildIdTemplateUri(getPools(baseUri));
    }

    public static URI getVips(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(VIPS).build();
    }

    public static String getVipTemplate(URI baseUri) {
        return buildIdTemplateUri(getVips(baseUri));
    }

    public static URI getVip(URI baseUri, UUID vipId) {
        return UriBuilder.fromUri(getVips(baseUri))
                .path(vipId.toString()).build();
    }
}
