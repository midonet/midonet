/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api;

import org.midonet.api.network.IP4MacPair;
import org.midonet.api.network.MacPort;
import org.midonet.cluster.data.Bridge;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.MAC;
import org.midonet.packets.IPv6Subnet;

import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.UUID;

public class ResourceUriBuilder {

    public static final String ROOT = "/";
    public static final String TENANTS = "/tenants";
    public static final String ROUTERS = "/routers";
    public static final String VLAN_BRIDGES = "/vlan_bridges";
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
    public static final String CHAINS = "/chains";
    public static final String RULES = "/rules";
    public static final String ROUTES = "/routes";
    public static final String BGP = "/bgps";
    public static final String AD_ROUTES = "/ad_routes";
    public static final String HOSTS = "/hosts";
    public static final String INTERFACES = "/interfaces";
    public static final String COMMANDS = "/commands";
    public static final String METRICS = "/metrics";
    public static final String FILTER = "/filter";
    public static final String QUERY = "/query";
    public static final String LINK = "/link";
    public static final String TUNNEL_ZONES = "/tunnel_zones";
    public static final String ID_TOKEN = "/{id}";
    public static final String TRACE_CONDITIONS ="/trace_conditions";
    public static final String TRACE = "/trace";
    public static final String SYSTEM_STATE = "/system_state";
    public static final String WRITE_VERSION = "/write_version";
    public static final String VLAN_ID = "/{vlanId}";
    public static final String MAC_ADDR = "/{macAddress}";
    public static final String PORT_ID = "/{portId}";
    public static final String PORT_ID_NO_SLASH = "{portId}";


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
        return UriBuilder.fromUri(getTenant(baseUri, tenantId)).path(ROUTERS)
                .build();
    }

    public static URI getTenantBridges(URI baseUri, String tenantId) {
        return UriBuilder.fromUri(getTenant(baseUri, tenantId)).path(BRIDGES)
                .build();
    }

    public static URI getTenantChains(URI baseUri, String tenantId) {
        return UriBuilder.fromUri(getTenant(baseUri, tenantId)).path(CHAINS)
                .build();
    }

    public static URI getTenantPortGroups(URI baseUri, String tenantId) {
        return UriBuilder.fromUri(getTenant(baseUri, tenantId))
                .path(PORT_GROUPS).build();
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

    public static URI getVlanBridges(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(VLAN_BRIDGES).build();
    }

    public static URI getVlanBridge(URI baseUri, UUID bridgeId) {
        return UriBuilder.fromUri(getVlanBridges(baseUri))
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

    public static URI getVlanBridgeTrunkPorts(URI baseUri, UUID bridgeId) {
        return UriBuilder.fromUri(getVlanBridge(baseUri, bridgeId)).path(PORTS)
                         .build();
    }

    public static URI getVlanBridgeInteriorPorts(URI baseUri, UUID bridgeId) {
        return UriBuilder.fromUri(getVlanBridge(baseUri, bridgeId))
                         .path(PEER_PORTS).build();
    }

    public static URI getBridgePorts(URI baseUri, UUID bridgeId) {
        return UriBuilder.fromUri(getBridge(baseUri, bridgeId)).path(PORTS)
                .build();
    }

    public static URI getBridgePeerPorts(URI baseUri, UUID bridgeId) {
        return UriBuilder.fromUri(getBridge(baseUri, bridgeId))
                .path(PEER_PORTS).build();
    }

    public static URI getBridgeDhcps(URI baseUri, UUID bridgeId) {
        return UriBuilder.fromUri(getBridge(baseUri, bridgeId)).path(DHCP)
                .build();
    }

    public static URI getBridgeDhcp(URI bridgeDhcpsUri, IntIPv4 subnetAddr) {
        return UriBuilder.fromUri(bridgeDhcpsUri).path(subnetAddr.toString())
                .build();
    }

    public static URI getBridgeDhcp(URI baseUri, UUID bridgeId,
            IntIPv4 subnetAddr) {
        URI dhcpsUri = getBridgeDhcps(baseUri, bridgeId);
        return getBridgeDhcp(dhcpsUri, subnetAddr);
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
        StringBuilder b = new StringBuilder();
        b.append(macToUri(mp.getMacAddr().toString()));
        b.append("_").append(mp.getPortId().toString());
        return b.toString();
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
        return UriBuilder.fromUri(bridgeUri).path(ARP_TABLE).build();
    }

    public static String ip4MacPairToUri(IP4MacPair pair) {
        StringBuilder b = new StringBuilder();
        b.append(pair.getIp()).append("_");
        b.append(macToUri(pair.getMac()));
        return b.toString();
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

    public static URI getRouterPorts(URI baseUri, UUID routerId) {
        return UriBuilder.fromUri(getRouter(baseUri, routerId)).path(PORTS)
                .build();
    }

    public static URI getRouterPeerPorts(URI baseUri, UUID routerId) {
        return UriBuilder.fromUri(getRouter(baseUri, routerId))
                .path(PEER_PORTS).build();
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

    public static URI getChainRules(URI baseUri, UUID chainId) {
        return UriBuilder.fromUri(getChain(baseUri, chainId)).path(RULES)
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

    public static URI getRouterRoutes(URI baseUri, UUID routerId) {
        return UriBuilder.fromUri(getRouter(baseUri, routerId)).path(ROUTES)
                .build();
    }

    public static URI getHosts(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(HOSTS).build();
    }

    public static URI getHost(URI baseUri, UUID hostId) {
        return UriBuilder.fromUri(getHosts(baseUri)).path(hostId.toString())
                .build();
    }

    public static URI getHostInterfaces(URI baseUri, UUID hostId) {
        return UriBuilder.fromUri(getHost(baseUri, hostId)).path(INTERFACES)
                .build();
    }

    public static URI getHostInterface(URI baseUri, UUID hostId,
            String name) {
        return UriBuilder.fromUri(getHostInterfaces(baseUri, hostId))
                .path(name).build();
    }

    public static URI getHostCommands(URI baseUri, UUID hostId) {
        return UriBuilder.fromUri(getHost(baseUri, hostId)).path(COMMANDS)
                .build();
    }

    public static URI getHostCommand(URI baseUri, UUID hostId, Integer id) {
        return UriBuilder.fromUri(getHostCommands(baseUri, hostId))
                .path(id.toString()).build();
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

    public static URI getPortPortGroups(URI baseUri, UUID portId) {
        return UriBuilder.fromUri(getPort(baseUri, portId)).path(
                PORT_GROUPS).build();
    }

    public static URI getPortGroup(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getPortGroups(baseUri)).path(id.toString())
                .build();
    }

    public static URI getPortGroupPorts(URI baseUri, UUID id) {
        return UriBuilder.fromUri(getPortGroup(baseUri, id))
                .path(PORTS).build();
    }

    public static URI getPortGroupPort(URI baseUri, UUID id, UUID portId) {
        return UriBuilder.fromUri(getPortGroupPorts(baseUri, id))
                .path(portId.toString()).build();
    }

    public static URI getMetrics(URI baseUri){
        return UriBuilder.fromUri(getRoot(baseUri)).path(METRICS).build();
    }

    public static URI getMetricsFilter(URI baseUri) {
        return UriBuilder.fromUri(getMetrics(baseUri)).path(FILTER).build();
    }

    public static URI getMetricsQuery(URI baseUri) {
        return UriBuilder.fromUri(getMetrics(baseUri)).path(QUERY).build();
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

    private static String buildIdTemplateUri(URI uri) {
        StringBuilder template = new StringBuilder(
                UriBuilder.fromUri(uri).build().toString());
        return template.append(ID_TOKEN).toString();
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
     * Generate a Vlan bridge URI template
     *
     * @param baseUri Base URI
     * @return Vlan Bridge template URI
     */
    public static String getVlanBridgeTemplate(URI baseUri) {
        return buildIdTemplateUri(getVlanBridges(baseUri));
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

    /**
     * Generate trace conditions URI
     *
     * @param baseUri Base service URI
     * @return Trace conditions URI
     */
    public static URI getTraceConditions(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(
                TRACE_CONDITIONS).build();
    }

    /**
     * Generate trace condition URI
     *
     * @param baseUri Base service URI
     * @param traceConditionId UUID of trace condition
     * @return Trace condition URI
     */
    public static URI getTraceCondition(URI baseUri, UUID traceConditionId) {
        if (baseUri == null || traceConditionId == null) {
            return null;
        }

        return UriBuilder.fromUri(getTraceConditions(baseUri)).path(
                 traceConditionId.toString()).build();
    }

    /**
     * Generate trace conditions URI template
     *
     * @param baseUri Base URI
     * @return Trace conditions template URI
     */
    public static String getTraceConditionTemplate(URI baseUri) {
        return buildIdTemplateUri(getTraceConditions(baseUri));
    }

    /**
     * Generate trace URI
     *
     * @param baseUri Base service URI
     * @return Trace URI
     */
    public static URI getTraces(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(TRACE).build();
    }

    /**
     * Generate trace URI
     *
     * @param baseUri Base service URI
     * @param traceId UUID of trace
     * @return Trace URI
     */
    public static URI getTrace(URI baseUri, UUID traceId) {
        if (baseUri == null || traceId == null) {
            return null;
        }

        return UriBuilder.fromUri(getTraces(baseUri)).path(
                 traceId.toString()).build();
    }

    /**
     * Generate trace URI template
     *
     * @param baseUri Base URI
     * @return Trace template URI
     */
    public static String getTraceTemplate(URI baseUri) {
        return buildIdTemplateUri(getTraces(baseUri));
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
}
