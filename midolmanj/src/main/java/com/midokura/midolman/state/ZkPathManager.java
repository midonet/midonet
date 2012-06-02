/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state;

import java.util.UUID;

import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;

/**
 * This class was created to have all state classes share the Zk path
 * information.
 */
public class ZkPathManager {

    protected String basePath = null;

    /**
     * Constructor.
     *
     * @param basePath
     *            Base path of Zk.
     */
    public ZkPathManager(String basePath) {
        this.basePath = basePath;
    }

    /**
     * @return the basePath
     */
    public String getBasePath() {
        return basePath;
    }

    /**
     * @param basePath
     *            the basePath to set
     */
    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    /**
     * Get GRE path.
     *
     * @return /gre
     */
    public String getGrePath() {
        return new StringBuilder(basePath).append("/gre").toString();
    }

    /**
     * Get GRE key path.
     *
     * @param greKeyId
     *            is the GRE key ID
     * @return /gre/greKey
     */
    public String getGreKeyPath(int greKeyId) {
        String formatted = String.format("%010d", greKeyId);
        return new StringBuilder(getGrePath()).append("/").append(formatted)
                .toString();
    }

    /**
     * Get ZK bridges path.
     *
     * @return /bridges
     */
    public String getBridgesPath() {
        return new StringBuilder(basePath).append("/bridges").toString();
    }

    /**
     * Get ZK bridge path.
     *
     * @param id
     *            Bridge UUID
     * @return /bridges/bridgeId
     */
    public String getBridgePath(UUID id) {
        return new StringBuilder(getBridgesPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get the path of a bridge's dynamic filtering database (mac to ports map).
     *
     * @param id
     *            Bridge UUID
     * @return /bridges/bridgeId/mac_ports
     */
    public String getBridgeMacPortsPath(UUID id) {
        return new StringBuilder(getBridgePath(id)).append("/mac_ports")
                .toString();
    }

    /**
     * Get the path of a bridge's port to location map.
     *
     * @param id
     *            Bridge UUID
     * @return /bridges/bridgeId/port_locations
     */
    public String getBridgePortLocationsPath(UUID id) {
        return new StringBuilder(getBridgePath(id)).append("/port_locations")
                .toString();
    }

    /**
     * Get ZK path for filtering state
     *
     * @return /filters
     */
    public String getFiltersPath() {
        return new StringBuilder(basePath).append("/filters").toString();
    }

    /**
     * Get ZK path for a port, bridge or router's filtering state.
     *
     * @param id
     *            Router, bridge or port UUID
     * @return /filters/parentId
     */
    public String getFilterPath(UUID id) {
        return new StringBuilder(getFiltersPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK path of the SNAT blocks in a filter state.
     *
     * @param id
     *            Router, bridge or port UUID
     * @return /filters/parentId/snat_blocks
     */
    public String getFilterSnatBlocksPath(UUID id) {
        return new StringBuilder(getFilterPath(id)).append("/snat_blocks")
                .toString();
    }

    /**
     * Get ZK router path.
     *
     * @return /routers
     */
    public String getRoutersPath() {
        return new StringBuilder(basePath).append("/routers").toString();
    }

    /**
     * Get ZK router path.
     *
     * @param id
     *            Router UUID
     * @return /routers/routerId
     */
    public String getRouterPath(UUID id) {
        return new StringBuilder(getRoutersPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK port path.
     *
     * @return /ports
     */
    public String getPortsPath() {
        return new StringBuilder(basePath).append("/ports").toString();
    }

    /**
     * Get ZK port path.
     *
     * @param id
     *            Port ID.
     * @return /ports/portId
     */
    public String getPortPath(UUID id) {
        return new StringBuilder(getPortsPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK port sets path.
     *
     * @return /port_sets
     */
    public String getPortSetsPath() {
        return new StringBuilder(basePath).append("/port_sets").toString();
    }

    /**
     * Get ZK port sets path.
     *
     * @return /port_sets/id
     */
    public String getPortSetPath(UUID id) {
        return new StringBuilder(getPortSetsPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK router port path.
     *
     * @param routerId
     *            Router UUID
     * @return /routers/routerId/ports
     */
    public String getRouterPortsPath(UUID routerId) {
        return new StringBuilder(getRouterPath(routerId)).append("/ports")
                .toString();
    }

    /**
     * Get ZK router port path.
     *
     * @param routerId
     *            Router UUID
     * @param portId
     *            Port UUID.
     * @return /routers/routerId/ports/portId
     */
    public String getRouterPortPath(UUID routerId, UUID portId) {
        return new StringBuilder(getRouterPortsPath(routerId)).append("/")
                .append(portId).toString();
    }

    /**
     * Get ZK bridge port path.
     *
     * @param bridgeId
     *            Bridge UUID
     * @return /bridges/bridgeId/ports
     */
    public String getBridgePortsPath(UUID bridgeId) {
        return new StringBuilder(getBridgePath(bridgeId)).append("/ports")
                .toString();
    }

    /**
     * Get ZK bridge port path.
     *
     * @param bridgeId
     *            Bridge UUID
     * @return /bridges/bridgeId/logical-ports
     */
    public String getBridgeLogicalPortsPath(UUID bridgeId) {
        return new StringBuilder(getBridgePath(bridgeId)).append(
                "/logical-ports").toString();
    }

    /**
     * Get ZK bridge port path.
     *
     * @param bridgeId
     *            Bridge UUID
     * @param portId
     *            Port UUID.
     * @return /bridges/bridgeId/ports/portId
     */
    public String getBridgePortPath(UUID bridgeId, UUID portId) {
        return new StringBuilder(getBridgePortsPath(bridgeId)).append("/")
                .append(portId).toString();
    }

    /**
     * Get ZK bridge port path.
     *
     * @param bridgeId
     *            Bridge UUID
     * @param portId
     *            Port UUID.
     * @return /bridges/bridgeId/logical-ports/portId
     */
    public String getBridgeLogicalPortPath(UUID bridgeId, UUID portId) {
        return new StringBuilder(getBridgeLogicalPortsPath(bridgeId))
                .append("/").append(portId).toString();
    }

    /**
     * Get ZK bridge dhcp path.
     *
     * @param bridgeId
     *            Bridge UUID
     * @return /bridges/bridgeId/dhcp
     */
    public String getBridgeDhcpPath(UUID bridgeId) {
        return new StringBuilder(getBridgePath(bridgeId)).append("/dhcp")
                .toString();
    }

    /**
     * Get ZK bridge dhcp subnet path.
     *
     * @param bridgeId
     *            Bridge UUID
     * @return /bridges/bridgeId/dhcp/subnetAddr:maskLen
     */
    public String getBridgeDhcpSubnetPath(UUID bridgeId, IntIPv4 subnetAddr) {
        return new StringBuilder(getBridgeDhcpPath(bridgeId)).append("/")
                .append(subnetAddr.toString()).toString();
    }

    /**
     * Get ZK bridge dhcp hosts path for a given subnet.
     *
     * @param bridgeId
     *            Bridge UUID
     * @return /bridges/bridgeId/dhcp/subnetAddr:maskLen/hosts
     */
    public String getBridgeDhcpHostsPath(UUID bridgeId, IntIPv4 subnetAddr) {
        return new StringBuilder(getBridgeDhcpSubnetPath(bridgeId, subnetAddr))
                .append("/hosts").toString();
    }

    /**
     * Get ZK bridge dhcp host path for a given subnet and mac address.
     *
     * @param bridgeId
     *            Bridge UUID
     * @return /bridges/bridgeId/dhcp/subnetAddr:maskLen/hosts/mac
     */
    public String getBridgeDhcpHostPath(UUID bridgeId, IntIPv4 subnetAddr,
            MAC macAddr) {
        return new StringBuilder(getBridgeDhcpHostsPath(bridgeId, subnetAddr))
                .append('/').append(macAddr.toString()).toString();
    }

    /**
     * Get ZK routes path.
     *
     * @return /routes
     */
    public String getRoutesPath() {
        return new StringBuilder(basePath).append("/routes").toString();
    }

    /**
     * Get ZK routes path. /routes/routeId
     *
     * @param id
     *            Route UUID
     * @return /routes/routeId
     */
    public String getRoutePath(UUID id) {
        return new StringBuilder(getRoutesPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK router routes path.
     *
     * @param routerId
     *            Router UUID
     * @return /routers/routerId/routes
     */
    public String getRouterRoutesPath(UUID routerId) {
        return new StringBuilder(getRouterPath(routerId)).append("/routes")
                .toString();
    }

    /**
     * Get ZK router routes path.
     *
     * @param routerId
     *            Router UUID
     * @param routeId
     *            Route UUID
     * @return /routers/routerId/routes/routeId
     */
    public String getRouterRoutePath(UUID routerId, UUID routeId) {
        return new StringBuilder(getRouterRoutesPath(routerId)).append("/")
                .append(routeId).toString();
    }

    /**
     * Get ZK port routes path.
     *
     * @param portId
     *            Port UUID
     * @return /ports/portId/routes
     */
    public String getPortRoutesPath(UUID portId) {
        return new StringBuilder(getPortPath(portId)).append("/routes")
                .toString();
    }

    /**
     * Get ZK port routes path.
     *
     * @param portId
     *            Port UUID
     * @param routeId
     *            Route ID.
     * @return /ports/portId/routes/routeId
     */
    public String getPortRoutePath(UUID portId, UUID routeId) {
        return new StringBuilder(getPortRoutesPath(portId)).append("/")
                .append(routeId).toString();
    }

    /**
     * Get ZK port groups path.
     *
     * @return /port_groups
     */
    public String getPortGroupsPath() {
        return new StringBuilder(basePath).append("/port_groups").toString();
    }

    /**
     * Get ZK port group path.
     *
     * @param id
     *            Group UUID.
     * @return /port_groups/groupId
     */
    public String getPortGroupPath(UUID id) {
        return new StringBuilder(getPortGroupsPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK port path inside a group.
     *
     * @param id
     *            Group UUID.
     * @param id
     *            Port UUID as a String.
     * @return /port_groups/groupId/portId
     */
    public String getPortInGroupPath(UUID id, String portId) {
        return new StringBuilder(getPortGroupPath(id)).append("/")
                .append(portId).toString();
    }

    /**
     * Get ZK rule chain path.
     *
     * @return /chains
     */
    public String getChainsPath() {
        return new StringBuilder(basePath).append("/chains").toString();
    }

    /**
     * Get ZK rule chain path.
     *
     * @param id
     *            Chain UUID.
     * @return /chains/chainId
     */
    public String getChainPath(UUID id) {
        return new StringBuilder(getChainsPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK rule path.
     *
     * @return /rules
     */
    public String getRulesPath() {
        return new StringBuilder(basePath).append("/rules").toString();
    }

    /**
     * Get ZK rule path.
     *
     * @param id
     *            Rule UUID.
     * @return /rules/ruleId
     */
    public String getRulePath(UUID id) {
        return new StringBuilder(getRulesPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK chain rule path.
     *
     * @param chainId
     *            Chain UUID
     * @return /chains/chainId/rules
     */
    public String getChainRulesPath(UUID chainId) {
        return new StringBuilder(getChainPath(chainId)).append("/rules")
                .toString();
    }

    /**
     * Get ZK chain rule path.
     *
     * @param chainId
     *            Chain UUID
     * @param ruleId
     *            Rule UUID.
     * @return /chains/chainId/rules/ruleId
     */
    public String getChainRulePath(UUID chainId, UUID ruleId) {
        return new StringBuilder(getChainRulesPath(chainId)).append("/")
                .append(ruleId).toString();
    }

    /**
     * Get ZK router routing table path.
     *
     * @param routerId
     *            Router UUID
     * @return /routers/routerId/routing_table
     */
    public String getRouterRoutingTablePath(UUID routerId) {
        return new StringBuilder(getRouterPath(routerId)).append(
                "/routing_table").toString();
    }

    public String getRouterArpTablePath(UUID routerId) {
        return new StringBuilder(getRouterPath(routerId)).append("/arp_table")
                .toString();
    }

    /**
     * Get ZK BGP path.
     *
     * @return /bgps
     */
    public String getBgpPath() {
        return new StringBuilder(basePath).append("/bgps").toString();
    }

    /**
     * Get ZK BGP path.
     *
     * @param id
     *            BGP UUID
     * @return /bgps/bgpId
     */
    public String getBgpPath(UUID id) {
        return new StringBuilder(getBgpPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK port BGP path.
     *
     * @param portId
     *            Port UUID
     * @return /ports/portId/bgps
     */
    public String getPortBgpPath(UUID portId) {
        return new StringBuilder(getPortPath(portId)).append("/bgps")
                .toString();
    }

    /**
     * Get ZK port BGP path.
     *
     * @param portId
     *            Port UUID
     * @param bgpId
     *            BGP UUID
     * @return /ports/portId/bgps/bgpId
     */
    public String getPortBgpPath(UUID portId, UUID bgpId) {
        return new StringBuilder(getPortBgpPath(portId)).append("/")
                .append(bgpId).toString();
    }

    /**
     * Get ZK advertising routes path.
     *
     * @return /ad_routes
     */
    public String getAdRoutesPath() {
        return new StringBuilder(basePath).append("/ad_routes").toString();
    }

    /**
     * Get ZK advertising routes path.
     *
     * @param id
     *            AdRoutes UUID
     * @return /ad_routes/adRouteId
     */
    public String getAdRoutePath(UUID id) {
        return new StringBuilder(getAdRoutesPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK BGP advertising routes path.
     *
     * @param bgpId
     *            BGP UUID
     * @return /bgps/bgpId/ad_routes
     */
    public String getBgpAdRoutesPath(UUID bgpId) {
        return new StringBuilder(getBgpPath(bgpId)).append("/ad_routes")
                .toString();
    }

    /**
     * Get ZK bgp advertising route path.
     *
     * @param bgpId
     *            BGP UUID
     * @param adRouteId
     *            Advertising route UUID
     * @return /bgps/bgpId/ad_routes/adRouteId
     */
    public String getBgpAdRoutePath(UUID bgpId, UUID adRouteId) {
        return new StringBuilder(getBgpAdRoutesPath(bgpId)).append("/")
                .append(adRouteId).toString();
    }

    /**
     * Get the path to the port to location map for the router network.
     *
     * @return /vrn_port_locations
     */
    public String getVRNPortLocationsPath() {
        return new StringBuilder(basePath).append("/vrn_port_locations")
                .toString();
    }

    /**
     * Get ZK VPN path.
     *
     * @return /vpns
     */
    public String getVpnPath() {
        return new StringBuilder(basePath).append("/vpns").toString();
    }

    /**
     * Get ZK VPN path.
     *
     * @param id
     *            VPN UUID
     * @return /vpns/vpnId
     */
    public String getVpnPath(UUID id) {
        return new StringBuilder(getVpnPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK port VPN path.
     *
     * @param portId
     *            Port UUID
     * @return /ports/portId/vpns
     */
    public String getPortVpnPath(UUID portId) {
        return new StringBuilder(getPortPath(portId)).append("/vpns")
                .toString();
    }

    /**
     * Get ZK port VPN path.
     *
     * @param portId
     *            Port UUID
     * @param vpnId
     *            VPN UUID
     * @return /ports/portId/vpns/vpnId
     */
    public String getPortVpnPath(UUID portId, UUID vpnId) {
        return new StringBuilder(getPortVpnPath(portId)).append("/")
                .append(vpnId).toString();
    }

    /**
     * Get ZK agent path.
     *
     * @return /agents
     */
    public String getAgentPath() {
        return new StringBuilder(basePath).append("/agents").toString();
    }

    /**
     * Get ZK agent port path.
     *
     * @return /agents/ports
     */
    public String getAgentPortPath() {
        return new StringBuilder(getAgentPath()).append("/ports").toString();
    }

    /**
     * Get ZK agent port path.
     *
     * @param portId
     *            Port UUID
     * @return /agents/ports/portId
     */
    public String getAgentPortPath(UUID portId) {
        return new StringBuilder(getAgentPortPath()).append("/").append(portId)
                .toString();
    }

    /**
     * Get ZK agent VPN path.
     *
     * @return /agents/vpns
     */
    public String getAgentVpnPath() {
        return new StringBuilder(getAgentPath()).append("/vpns").toString();
    }

    /**
     * Get ZK agent VPN path.
     *
     * @param vpnId
     *            VPN UUID
     * @return /agents/vpns/vpnId
     */
    public String getAgentVpnPath(UUID vpnId) {
        return new StringBuilder(getAgentVpnPath()).append("/").append(vpnId)
                .toString();
    }

    /**
     * Get ZK hosts path.
     *
     * @return /hosts
     */
    public String getHostsPath() {
        return new StringBuilder(basePath).append("/hosts").toString();
    }

    /**
     * Get ZK router path.
     *
     * @param id
     *            Host UUID
     * @return /hosts/&lt;hostId&gt;
     */
    public String getHostPath(UUID id) {
        return new StringBuilder(getHostsPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK hosts path.
     *
     * @param hostId
     *            Host UUID
     * @return /hosts/&lt;hostId&gt;/interfaces
     */
    public String getHostInterfacesPath(UUID hostId) {
        return new StringBuilder(getHostPath(hostId)).append("/interfaces")
                .toString();
    }

    /**
     * Get ZK host interface path.
     *
     * @param hostId
     *            Host UUID
     * @param interfaceId
     *            Host interface UUID
     * @return /hosts/&lt;hostId&gt;/interfaces/&lt;interfaceId&gt;
     */
    public String getHostInterfacePath(UUID hostId, UUID interfaceId) {
        return new StringBuilder(getHostPath(hostId)).append("/interfaces/")
                .append(interfaceId.toString()).toString();
    }

    /**
     * Get ZK host commands path.
     *
     * @param hostId
     *            Host UUID
     * @return /hosts/&lt;hostId&gt;/commands
     */
    public String getHostCommandsPath(UUID hostId) {
        return new StringBuilder(getHostPath(hostId)).append("/commands")
                .toString();
    }

    /**
     * Get ZK a specific host commands path.
     *
     * @param hostId
     *            Host UUID
     * @param commandId
     *            Command Id
     * @return /hosts/&lt;hostId&gt;/commands/&lt;commandIs&gt;
     */
    public String getHostCommandPath(UUID hostId, Integer commandId) {
        return new StringBuilder(getHostCommandsPath(hostId)).append("/")
                .append(String.format("%010d", commandId)).toString();
    }

    /**
     * Get ZK commands error log path
     *
     * @param hostId
     * @return /hosts/&lt;hostId&gt;/errors
     */
    public String getHostCommandErrorLogsPath(UUID hostId) {
        return new StringBuilder(getHostPath(hostId)).append("/errors")
                .toString();
    }

    /**
     * Get the error log path of a specific host
     *
     * @param hostId
     * @param commandId
     * @return /hosts/&lt;hostId&gt;/errors/&lt;commandIs&gt;
     */
    public String getHostCommandErrorLogPath(UUID hostId, Integer commandId) {
        return new StringBuilder(getHostCommandErrorLogsPath(hostId))
                .append("/").append(String.format("%010d", commandId))
                .toString();
    }
}
