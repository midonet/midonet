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
package org.midonet.midolman.state;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.UUID;

import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.IPv6Subnet;
import org.midonet.packets.MAC;

/**
 * This class was created to have all state classes share the Zk path
 * information.
 */
public class ZkPathManager {

    public static final String TUNNEL_ZONES = "tunnel_zones";
    public static final String MEMBERSHIPS = "memberships";
    public static final String FLOODING_PROXY_WEIGHT = "/flooding_proxy_weight";

    private static final String UTF8 = "UTF-8";

    protected String basePath = null;

    /**
     * Constructor.
     *
     * @param basePath Base path of Zk.
     */
    public ZkPathManager(String basePath) {
        setBasePath(basePath);
    }

    /**
     * @return the basePath
     */
    public String getBasePath() {
        return basePath;
    }

    private StringBuilder basePath() {
        return new StringBuilder(basePath);
    }

    /**
     * @param basePath the basePath to set
     */
    public void setBasePath(String basePath) {
        this.basePath = basePath;
        if (this.basePath == null) {
            this.basePath = "";
        }
    }

    public String getVersionPath(String version) {
        return getVersionsPath() + "/" + version;
    }


    public String getHostVersionPath(UUID uuid, String version) {
        return getVersionPath(version) + "/" + uuid;
    }

    public String getTraceRequestsPath() {
        return basePath().append("/traces").toString();
    }

    public String getTraceRequestPath(UUID uuid) {
        return getTraceRequestsPath() + "/" + uuid;
    }

    private StringBuilder buildHealthMonitorLeaderDirPath() {
        return new StringBuilder(getBasePath()).append("/hm_leader");
    }

    public String getHealthMonitorLeaderDirPath() {
        return buildHealthMonitorLeaderDirPath().toString();
    }

    public String getTunnelPath() {
        return buildTunnelPath().toString();
    }

    protected StringBuilder buildTunnelPath() {
        // XXX(guillermo) s/"gre"/"tunnel"/ ?
        return basePath().append("/gre");
    }

    public String getDeviceStatusPath() {
        return buildDeviceStatusPath().toString();
    }

    protected StringBuilder buildDeviceStatusPath() {
        return basePath().append("/device_status");
    }

    public String getBgpStatusPath() {
        return buildBgpStatusPath().toString();
    }

    protected StringBuilder buildBgpStatusPath() {
        return buildDeviceStatusPath().append("/bgp");
    }

    /**
     * Get tunnel (GRE) key path.
     *
     * @param tunnelKeyId is the tunnel key ID
     * @return /gre/tunnelKeyId
     */
    public String getTunnelPath(int tunnelKeyId) {
        return buildTunnelPath(tunnelKeyId).toString();
    }

    protected StringBuilder buildTunnelPath(int tunnelKeyId) {
        return buildTunnelPath().append("/").append(
            String.format("%010d", tunnelKeyId));
    }

    /**
     * Get ZK bridges path.
     *
     * @return /bridges
     */
    public String getBridgesPath() {
        return buildBridgesPath().toString();
    }

    /**
     * Get ZK bridge path.
     *
     * @param id Bridge UUID
     * @return /bridges/bridgeId
     */
    public String getBridgePath(UUID id) {
        return buildBridgePath(id).toString();
    }

    protected StringBuilder buildBridgePath(UUID id) {
        return buildBridgesPath().append("/").append(id);
    }

    protected StringBuilder buildBridgeMacPortsPath(UUID id, short vlanId) {
        StringBuilder builder = buildBridgePath(id);
        if (vlanId != Bridge.UNTAGGED_VLAN_ID)
            builder.append("/vlans/").append(vlanId);
        builder.append("/mac_ports");
        return builder;
    }

    /**
     * Get the path of a bridge's dynamic filtering database (mac to ports map).
     *
     * @param id Bridge UUID
     * @param vlanId VLAN ID. Bridge.UNTAGGED_VLAN_ID for the untagged VLAN.
     * @return If vlanId == UNTAGGED_VLAN_ID: /bridges/bridgeId/mac_ports.
     *         Otherwise: /bridges/bridgeId/vlans/vlanId/mac_ports.
     */
    public String getBridgeMacPortsPath(UUID id, short vlanId) {
        return buildBridgeMacPortsPath(id, vlanId).toString();
    }

    public String getBridgeMacPortsPath(UUID id) {
        return getBridgeMacPortsPath(id, Bridge.UNTAGGED_VLAN_ID);
    }

    public String getBridgeMacPortEntryPath(UUID bridgeId, short vlanId,
                                            String macEntry) {
        StringBuilder macPortPath = buildBridgeMacPortsPath(bridgeId, vlanId);
        macPortPath.append(macEntry);
        return macPortPath.toString();
    }

    public String getBridgeMacPortEntryPath(Neutron.NeutronPort port) {
        MAC mac = MAC.fromString(port.getMacAddress());
        UUID portId = UUIDUtil.fromProto(port.getId());
        UUID networkId = UUIDUtil.fromProto(port.getNetworkId());
        return getBridgeMacPortEntryPath(
            networkId, Bridge.UNTAGGED_VLAN_ID,
            MacPortMap.encodePersistentPath(mac, portId));
    }

    /**
     * Get the path of VLANs under a bridge.
     *
     * @param id Bridge UUID
     * @return /bridges/bridgeId/vlans
     */
    public String getBridgeVlansPath(UUID id) {
        return buildBridgeVlansPath(id).toString();
    }

    protected StringBuilder buildBridgeVlansPath(UUID id) {
        return buildBridgePath(id).append("/vlans");
    }

    /**
     * Get the path of a specific VLAN under a bridge.
     *
     * @param id Bridge UUID
     * @param vlanId Vlan Short
     * @return /bridges/bridgeId/vlans/vlanId
     */
    public String getBridgeVlanPath(UUID id, Short vlanId) {
        return buildBridgeVlanPath(id, vlanId).toString();
    }

    protected StringBuilder buildBridgeVlanPath(UUID id, Short vlanId) {
        return buildBridgeVlansPath(id).append("/").append(vlanId);
    }

    /**
     +     * Get the path of a bridge's arp table.
     +     *
     +     * @param id Bridge UUID
     +     * @return /bridges/bridgeId/ip4_mac_map
     +     */
    public String getBridgeIP4MacMapPath(UUID id) {
        return buildBridgePath(id).append("/ip4_mac_map").toString();
    }

    /**
     * Get ZK path for filtering state
     *
     * @return /filters
     */
    public String getFiltersPath() {
        return buildFiltersPath().toString();
    }

    protected StringBuilder buildFiltersPath() {
        return basePath().append("/filters");
    }

    /**
     * Get ZK path for a port, bridge or router's filtering state.
     *
     * @param id Router, bridge or port UUID
     * @return /filters/parentId
     */
    public String getFilterPath(UUID id) {
        return buildFilterPath(id).toString();
    }

    private StringBuilder buildFilterPath(UUID id) {
        return buildFiltersPath().append("/").append(id);
    }

    /**
     * Get Versions path.
     *
     * @return /version
     */
    public String getVersionsPath() {
        return basePath().append("/versions").toString();
    }

    /**
     * Get write-version path.
     *
     * @return /write_version
     */
    public String getWriteVersionPath() {
        return basePath().append("/write_version").toString();
    }

    /**
     * build system-state path.
     *
     * @return /system_state StringBuilder
     */
    public StringBuilder buildSystemStatePath() {
        return basePath().append("/system_state");
    }

    /**
     * Get system-state path.
     *
     * @return /system_state
     */
    public String getSystemStatePath() {
        return basePath().append("/system_state").toString();
    }


    /**
     * Get system-state path.
     *
     * @return /system_state/UPGRADE
     */
    public String getSystemStateUpgradePath() {
        return buildSystemStatePath().append("/UPGRADE").toString();
    }

    /**
     * Get system-state path.
     *
     * @return /system_state/API_RESTRICTED
     */
    public String getConfigReadOnlyPath() {
        return buildSystemStatePath().append("/CONFIG_READ_ONLY").toString();
    }

    /**
     * Get ZK router path.
     *
     * @return /routers
     */
    public String getRoutersPath() {
        return buildRoutersPath().toString();
    }

    private StringBuilder buildRoutersPath() {
        return basePath().append("/routers");
    }

    /**
     * Get ZK router path.
     *
     * @param id Router UUID
     * @return /routers/routerId
     */
    public String getRouterPath(UUID id) {
        return buildRouterPath(id).toString();
    }

    private StringBuilder buildRouterPath(UUID id) {
        return buildRoutersPath().append("/").append(id);
    }

    /**
     * Get ZK port path.
     *
     * @return /ports
     */
    public String getPortsPath() {
        return buildPortsPath().toString();
    }

    private StringBuilder buildPortsPath() {
        return basePath().append("/ports");
    }

    public String getPortActivePath(UUID id) {
        return buildPortPath(id).append("/active").toString();
    }

    /**
     * Get ZK port path.
     *
     * @param id Port ID.
     * @return /ports/portId
     */
    public String getPortPath(UUID id) {
        return buildPortPath(id).toString();
    }

    private StringBuilder buildPortPath(UUID id) {
        return buildPortsPath().append("/").append(id);
    }

    /**
     * Get ZK router port path.
     *
     * @param routerId Router UUID
     * @return /routers/routerId/ports
     */
    public String getRouterPortsPath(UUID routerId) {
        return buildRouterPortsPath(routerId).toString();
    }

    private StringBuilder buildRouterPortsPath(UUID routerId) {
        return buildRouterPath(routerId).append("/ports");
    }

    /**
     * Get ZK router port path.
     *
     * @param routerId Router UUID
     * @param portId   Port UUID.
     * @return /routers/routerId/ports/portId
     */
    public String getRouterPortPath(UUID routerId, UUID portId) {
        return buildRouterPortPath(routerId, portId).toString();
    }

    private StringBuilder buildRouterPortPath(UUID routerId, UUID portId) {
        return buildRouterPortsPath(routerId)
            .append("/").append(portId);
    }

    /**
     * Get ZK bridge port path.
     *
     * @param bridgeId Bridge UUID
     * @return /bridges/bridgeId/ports
     */
    public String getBridgePortsPath(UUID bridgeId) {
        return buildBridgePortsPath(bridgeId).toString();
    }

    private StringBuilder buildBridgePortsPath(UUID bridgeId) {
        return buildBridgePath(bridgeId).append("/ports");
    }

    /**
     * Get ZK bridge port path.
     *
     * @param bridgeId Bridge UUID
     * @return /bridges/bridgeId/logical-ports
     */
    public String getBridgeLogicalPortsPath(UUID bridgeId) {
        return buildBridgeLogicalPortsPath(bridgeId).toString();
    }

    private StringBuilder buildBridgeLogicalPortsPath(UUID bridgeId) {
        return buildBridgePath(bridgeId).append("/logical-ports");
    }

    /**
     * Get ZK bridge port path.
     *
     * @param bridgeId Bridge UUID
     * @param portId   Port UUID.
     * @return /bridges/bridgeId/ports/portId
     */
    public String getBridgePortPath(UUID bridgeId, UUID portId) {
        return buildBridgePortPath(bridgeId, portId).toString();
    }

    private StringBuilder buildBridgePortPath(UUID bridgeId, UUID portId) {
        return buildBridgePortsPath(bridgeId).append("/")
            .append(portId);
    }

    /**
     * Get ZK bridge port path.
     *
     * @param bridgeId Bridge UUID
     * @param portId   Port UUID.
     * @return /bridges/bridgeId/logical-ports/portId
     */
    public String getBridgeLogicalPortPath(UUID bridgeId, UUID portId) {
        return buildBridgeLogicalPortPath(bridgeId, portId).toString();
    }

    private StringBuilder buildBridgeLogicalPortPath(UUID bridgeId, UUID portId) {
        return buildBridgeLogicalPortsPath(bridgeId)
            .append("/").append(portId);
    }

    /**
     * Get ZK bridge dhcp path.
     *
     * @param bridgeId Bridge UUID
     * @return /bridges/bridgeId/dhcp
     */
    public String getBridgeDhcpPath(UUID bridgeId) {
        return buildBridgeDhcpPath(bridgeId).toString();
    }

    private StringBuilder buildBridgeDhcpPath(UUID bridgeId) {
        return buildBridgePath(bridgeId).append("/dhcp");
    }

    /**
     * Get ZK bridge dhcp subnet path.
     *
     * @param bridgeId Bridge UUID
     * @return /bridges/bridgeId/dhcp/subnetAddr:maskLen
     */
    public String getBridgeDhcpSubnetPath(UUID bridgeId, IPv4Subnet subnetAddr) {
        return buildBridgeDhcpSubnetPath(bridgeId, subnetAddr).toString();
    }

    private StringBuilder buildBridgeDhcpSubnetPath(UUID bridgeId,
                                                    IPv4Subnet subnetAddr) {
        return buildBridgeDhcpPath(bridgeId).append("/")
            .append(subnetAddr.toZkString());
    }

    /**
     * Get ZK bridge dhcp hosts path for a given subnet.
     *
     * @param bridgeId Bridge UUID
     * @return /bridges/bridgeId/dhcp/subnetAddr:maskLen/hosts
     */
    public String getBridgeDhcpHostsPath(UUID bridgeId, IPv4Subnet subnetAddr) {
        return buildBridgeDhcpHostsPath(bridgeId, subnetAddr).toString();
    }

    private StringBuilder buildBridgeDhcpHostsPath(UUID bridgeId,
                                                   IPv4Subnet subnetAddr) {
        return new StringBuilder(getBridgeDhcpSubnetPath(bridgeId, subnetAddr))
            .append("/hosts");
    }

    /**
     * Get ZK bridge dhcp host path for a given subnet and mac address.
     *
     * @param bridgeId Bridge UUID
     * @return /bridges/bridgeId/dhcp/subnetAddr:maskLen/hosts/mac
     */
    public String getBridgeDhcpHostPath(UUID bridgeId, IPv4Subnet subnetAddr,
                                        MAC macAddr) {
        return buildBridgeDhcpHostPath(bridgeId, subnetAddr,
                                       macAddr).toString();
    }

    private StringBuilder buildBridgeDhcpHostPath(UUID bridgeId,
                                                  IPv4Subnet subnetAddr,
                                                  MAC macAddr) {
        return new StringBuilder(getBridgeDhcpHostsPath(bridgeId, subnetAddr))
            .append('/').append(macAddr.toString());
    }

    /**
     * Get ZK bridge dhcpV6 path.
     *
     * @param bridgeId Bridge UUID
     * @return /bridges/bridgeId/dhcpV6
     */
    public String getBridgeDhcpV6Path(UUID bridgeId) {
        return buildBridgeDhcpV6Path(bridgeId).toString();
    }

    private StringBuilder buildBridgeDhcpV6Path(UUID bridgeId) {
        return buildBridgePath(bridgeId).append("/dhcpV6");
    }

    /**
     * Get ZK bridge dhcpV6 subnet path.
     *
     * @param bridgeId Bridge UUID
     * @return /bridges/bridgeId/dhcpV6/prefix:len
     */
    public String getBridgeDhcpSubnet6Path(UUID bridgeId, IPv6Subnet prefix) {
        return buildBridgeDhcpSubnet6Path(bridgeId, prefix).toString();
    }

    private StringBuilder buildBridgeDhcpSubnet6Path(UUID bridgeId,
                                                     IPv6Subnet prefix) {
        return buildBridgeDhcpV6Path(bridgeId).append("/")
            .append(prefix.toZkString());
    }

    /**
     * Get ZK bridge dhcpV6 hosts path for a given subnet6.
     *
     * @param bridgeId Bridge UUID
     * @return /bridges/bridgeId/dhcpV6/prefix:len/hosts
     */
    public String getBridgeDhcpV6HostsPath(UUID bridgeId, IPv6Subnet prefix) {
        return buildBridgeDhcpV6HostsPath(bridgeId, prefix).toString();
    }

    private StringBuilder buildBridgeDhcpV6HostsPath(UUID bridgeId,
                                                     IPv6Subnet prefix) {
        return new StringBuilder(getBridgeDhcpSubnet6Path(bridgeId, prefix))
            .append("/hosts");
    }

    /**
     * Get ZK bridge dhcpV6 host path for a given subnet and client ID.
     *
     * @param bridgeId Bridge UUID
     * @return /bridges/bridgeId/dhcpV6/prefix:len/hosts/clientId
     */
    public String getBridgeDhcpV6HostPath(UUID bridgeId, IPv6Subnet prefix,
                                        String clientId) {
        return buildBridgeDhcpV6HostPath(bridgeId, prefix, clientId).toString();
    }

    private StringBuilder buildBridgeDhcpV6HostPath(UUID bridgeId,
                                                    IPv6Subnet prefix,
                                                    String clientId) {
        return new StringBuilder(getBridgeDhcpV6HostsPath(bridgeId, prefix))
            .append('/').append(clientId);
    }

    public String getVlanBridgesPath() {
        return buildVlanBridgesPath().toString();
    }

    protected StringBuilder buildBridgesPath() {
        return basePath().append("/bridges");
    }

    protected StringBuilder buildVlanBridgesPath() {
        return basePath().append("/vlan-bridges");
    }

    /**
     * Get ZK bridge tags path.
     *
     * @param bridgeId Bridge UUID
     * @return /bridges/bridgeId/tags
     */
    public String getBridgeTagsPath(UUID bridgeId) {
        return buildBridgeTagsPath(bridgeId).toString();
    }

    private StringBuilder buildBridgeTagsPath(UUID bridgeId) {
        return buildBridgePath(bridgeId).append("/tags");
    }

    /**
     * Get ZK routes path.
     *
     * @return /routes
     */
    public String getRoutesPath() {
        return buildRoutesPath().toString();
    }

    private StringBuilder buildRoutesPath() {
        return basePath().append("/routes");
    }

    /**
     * Get ZK routes path. /routes/routeId
     *
     * @param id Route UUID
     * @return /routes/routeId
     */
    public String getRoutePath(UUID id) {
        return buildRoutePath(id).toString();
    }

    private StringBuilder buildRoutePath(UUID id) {
        return new StringBuilder(getRoutesPath()).append("/").append(id);
    }

    /**
     * Get ZK router routes path.
     *
     * @param routerId Router UUID
     * @return /routers/routerId/routes
     */
    public String getRouterRoutesPath(UUID routerId) {
        return buildRouterRoutesPath(routerId).toString();
    }

    private StringBuilder buildRouterRoutesPath(UUID routerId) {
        return buildRouterPath(routerId).append("/routes");
    }

    /**
     * Get ZK router routes path.
     *
     * @param routerId Router UUID
     * @param routeId  Route UUID
     * @return /routers/routerId/routes/routeId
     */
    public String getRouterRoutePath(UUID routerId, UUID routeId) {
        return buildRouterRoutePath(routerId, routeId).toString();
    }

    private StringBuilder buildRouterRoutePath(UUID routerId, UUID routeId) {
        return buildRouterRoutesPath(routerId).append("/")
            .append(routeId);
    }

    /**
     * Get ZK port routes path.
     *
     * @param portId Port UUID
     * @return /ports/portId/routes
     */
    public String getPortRoutesPath(UUID portId) {
        return buildPortRoutesPath(portId).toString();
    }

    private StringBuilder buildPortRoutesPath(UUID portId) {
        return buildPortPath(portId).append("/routes");
    }

    /**
     * Get ZK port routes path.
     *
     * @param portId  Port UUID
     * @param routeId Route ID.
     * @return /ports/portId/routes/routeId
     */
    public String getPortRoutePath(UUID portId, UUID routeId) {
        return buildPortRoutePath(portId, routeId).toString();
    }

    private StringBuilder buildPortRoutePath(UUID portId, UUID routeId) {
        return buildPortRoutesPath(portId).append("/")
            .append(routeId);
    }

    /**
     * Get ZK port groups path.
     *
     * @return /port_groups
     */
    public String getPortGroupsPath() {
        return buildPortGroupsPath().toString();
    }

    private StringBuilder buildPortGroupsPath() {
        return basePath().append("/port_groups");
    }

    /**
     * Get ZK port group path.
     *
     * @param id Group UUID.
     * @return /port_groups/groupId
     */
    public String getPortGroupPath(UUID id) {
        return buildPortGroupPath(id)
            .toString();
    }

    private StringBuilder buildPortGroupPath(UUID id) {
        return new StringBuilder(getPortGroupsPath()).append("/").append(id);
    }

    /**
     * Get ZK port group ports path.
     *
     * @param id Group UUID.
     * @return /port_groups/groupId/ports
     */
    public String getPortGroupPortsPath(UUID id) {
        return buildPortGroupPortsPath(id).toString();
    }

    private StringBuilder buildPortGroupPortsPath(UUID id) {
        return buildPortGroupPath(id).append("/ports");
    }

    /**
     * Get ZK port group port path.
     *
     * @param id Group UUID.
     * @return /port_groups/groupId/ports/portId
     */
    public String getPortGroupPortPath(UUID id, UUID portId) {
        return buildPortGroupPortPath(id, portId).toString();
    }

    private StringBuilder buildPortGroupPortPath(UUID id, UUID portId) {
        return buildPortGroupPortsPath(id).append("/").append(portId);
    }

    /**
     * Get ZK port group rules path.
     *
     * @param id Group UUID.
     * @return /port_groups/groupId/rules
     */
    public String getPortGroupRulesPath(UUID id) {
        return buildPortGroupRulesPath(id).toString();
    }

    private StringBuilder buildPortGroupRulesPath(UUID id) {
        return buildPortGroupPath(id).append("/rules");
    }

    /**
     * Get ZK port group rule path.
     *
     * @param id Group UUID.
     * @return /port_groups/groupId/rules/ruleId
     */
    public String getPortGroupRulePath(UUID id, UUID ruleId) {
        return buildPortGroupRulePath(id, ruleId).toString();
    }

    private StringBuilder buildPortGroupRulePath(UUID id, UUID ruleId) {
        return buildPortGroupRulesPath(id).append("/").append(ruleId);
    }

    public String getChainsPath() {
        return buildChainsPath().toString();
    }

    private StringBuilder buildChainsPath() {
        return basePath().append("/chains");
    }

    /**
     * Get ZK rule chain path.
     *
     * @param id Chain UUID.
     * @return /chains/chainId
     */
    public String getChainPath(UUID id) {
        return buildChainPath(id).toString();
    }

    private StringBuilder buildChainPath(UUID id) {
        return new StringBuilder(getChainsPath()).append("/").append(id);
    }

    /**
     * Get ZK rule path.
     *
     * @return /rules
     */
    public String getRulesPath() {
        return buildRulesPath().toString();
    }

    private StringBuilder buildRulesPath() {
        return basePath().append("/rules");
    }

    /**
     * Get ZK rule path.
     *
     * @param id Rule UUID.
     * @return /rules/ruleId
     */
    public String getRulePath(UUID id) {
        return buildRulePath(id).toString();
    }

    private StringBuilder buildRulePath(UUID id) {
        return new StringBuilder(getRulesPath()).append("/").append(id);
    }

    /**
     * Get ZK chain rule path.
     *
     * @param chainId Chain UUID
     * @return /chains/chainId/rules
     */
    public String getChainRulesPath(UUID chainId) {
        return buildChainRulesPath(chainId).toString();
    }

    private StringBuilder buildChainRulesPath(UUID chainId) {
        return buildChainPath(chainId).append("/rules");
    }

    /**
     * Get ZK chain refs path.
     *
     * @param chainId Chain UUID
     * @return /chains/chainId/refs
     */
    public String getChainBackRefsPath(UUID chainId) {
        return buildChainBackRefsPath(chainId).toString();
    }

    private StringBuilder buildChainBackRefsPath(UUID chainId) {
        return buildChainPath(chainId).append("/refs");
    }

    /**
     * Get ZK chain ref path.
     *
     * @param chainId Chain UUID
     * @return /chains/chainId/refs/type:deviceId
     */
    public String getChainBackRefPath(UUID chainId, String deviceType,
                                      UUID deviceId) {
        return buildChainBackRefPath(chainId, deviceType, deviceId).toString();
    }

    private StringBuilder buildChainBackRefPath(UUID chainId, String deviceType,
                                                UUID deviceId) {
        return buildChainBackRefsPath(chainId)
                .append("/").append(deviceType).append(":").append(deviceId);
    }

    public String getTypeFromBackRef(String backRef) {
        return backRef.split(":")[0];
    }

    public UUID getUUIDFromBackRef(String backRef) {
        return UUID.fromString(backRef.split(":")[1]);
    }

    /**
     * Get ZK router routing table path.
     *
     * @param routerId Router UUID
     * @return /routers/routerId/routing_table
     */
    public String getRouterRoutingTablePath(UUID routerId) {
        return buildRouterRoutingTablePath(routerId).toString();
    }

    private StringBuilder buildRouterRoutingTablePath(UUID routerId) {
        return buildRouterPath(routerId).append("/routing_table");
    }

    public String getRouterArpTablePath(UUID routerId) {
        return buildRouterArpTablePath(routerId).toString();
    }

    private StringBuilder buildRouterArpTablePath(UUID routerId) {
        return buildRouterPath(routerId).append("/arp_table");
    }

    /**
     * Get ZK BGP path.
     *
     * @return /bgps
     */
    public String getBgpPath() {
        return buildBgpPath().toString();
    }

    private StringBuilder buildBgpPath() {
        return basePath().append("/bgps");
    }

    /**
     * Get ZK BGP path.
     *
     * @param id BGP UUID
     * @return /bgps/bgpId
     */
    public String getBgpPath(UUID id) {
        return buildBgpPath(id).toString();
    }

    private StringBuilder buildBgpPath(UUID id) {
        return new StringBuilder(getBgpPath()).append("/").append(id);
    }

    /**
     * Get ZK port BGP path.
     *
     * @param portId Port UUID
     * @return /ports/portId/bgps
     */
    public String getPortBgpPath(UUID portId) {
        return buildPortBgpPath(portId).toString();
    }

    private StringBuilder buildPortBgpPath(UUID portId) {
        return buildPortPath(portId).append("/bgps");
    }

    /**
     * Get ZK port BGP path.
     *
     * @param portId Port UUID
     * @param bgpId  BGP UUID
     * @return /ports/portId/bgps/bgpId
     */
    public String getPortBgpPath(UUID portId, UUID bgpId) {
        return buildPortBgpPath(portId, bgpId).toString();
    }

    private StringBuilder buildPortBgpPath(UUID portId, UUID bgpId) {
        return buildPortBgpPath(portId).append("/")
            .append(bgpId);
    }

    /**
     * Get ZK advertising routes path.
     *
     * @return /ad_routes
     */
    public String getAdRoutesPath() {
        return buildAdRoutesPath().toString();
    }

    private StringBuilder buildAdRoutesPath() {
        return basePath().append("/ad_routes");
    }

    /**
     * Get ZK advertising routes path.
     *
     * @param id AdRoutes UUID
     * @return /ad_routes/adRouteId
     */
    public String getAdRoutePath(UUID id) {
        return buildAdRoutePath(id)
            .toString();
    }

    private StringBuilder buildAdRoutePath(UUID id) {
        return new StringBuilder(getAdRoutesPath()).append("/").append(id);
    }

    /**
     * Get ZK BGP advertising routes path.
     *
     * @param bgpId BGP UUID
     * @return /bgps/bgpId/ad_routes
     */
    public String getBgpAdRoutesPath(UUID bgpId) {
        return buildBgpAdRoutesPath(bgpId).toString();
    }

    private StringBuilder buildBgpAdRoutesPath(UUID bgpId) {
        return buildBgpPath(bgpId).append("/ad_routes");
    }

    /**
     * Get ZK bgp advertising route path.
     *
     * @param bgpId     BGP UUID
     * @param adRouteId Advertising route UUID
     * @return /bgps/bgpId/ad_routes/adRouteId
     */
    public String getBgpAdRoutePath(UUID bgpId, UUID adRouteId) {
        return buildBgpAdRoutePath(bgpId, adRouteId).toString();
    }

    private StringBuilder buildBgpAdRoutePath(UUID bgpId, UUID adRouteId) {
        return buildBgpAdRoutesPath(bgpId).append("/")
            .append(adRouteId);
    }

    /**
     * Get ZK agent path.
     *
     * @return /agents
     */
    public String getAgentPath() {
        return buildAgentPath().toString();
    }

    private StringBuilder buildAgentPath() {
        return basePath().append("/agents");
    }

    /**
     * Get ZK agent port path.
     *
     * @return /agents/ports
     */
    public String getAgentPortPath() {
        return buildAgentPortPath().toString();
    }

    private StringBuilder buildAgentPortPath() {
        return new StringBuilder(getAgentPath()).append("/ports");
    }

    public String getTunnelZonesPath() {
        return buildTunnelZonesPath().toString();
    }

    private StringBuilder buildTunnelZonesPath() {
        return basePath().append("/").append(TUNNEL_ZONES);
    }

    public String getTunnelZonePath(UUID id) {
        return buildTunnelZonePath(id).toString();
    }

    private StringBuilder buildTunnelZonePath(UUID id) {
        return buildTunnelZonesPath().append("/").append(id);
    }

    /**
     * Get ZK hosts path.
     *
     * @return /hosts
     */
    public String getHostsPath() {
        return buildHostsPath().toString();
    }

    private StringBuilder buildHostsPath() {
        return basePath().append("/hosts");
    }

    /**
     * Get ZK host commands path.
     *
     * @param hostId Host UUID
     * @return /hosts/&lt;hostId&gt;/commands
     */
    public String getHostCommandsPath(UUID hostId) {
        return buildHostCommandsPath(hostId).toString();
    }

    private StringBuilder buildHostCommandsPath(UUID hostId) {
        return buildHostPath(hostId).append("/commands");
    }

    /**
     * Get ZK commands error log path
     *
     * @param hostId Host UUID
     * @return /hosts/&lt;hostId&gt;/errors
     */
    public String getHostCommandErrorLogsPath(UUID hostId) {
        return buildHostCommandErrorLogsPath(hostId).toString();
    }

    private StringBuilder buildHostCommandErrorLogsPath(UUID hostId) {
        return buildHostPath(hostId).append("/errors");
    }

    /**
     * Get ZK router path.
     *
     * @param id Host UUID
     * @return /hosts/&lt;hostId&gt;
     */
    public String getHostPath(UUID id) {
        return buildHostPath(id).toString();
    }

    private StringBuilder buildHostPath(UUID id) {
        return new StringBuilder(getHostsPath()).append("/").append(id);
    }

    /**
     * Get ZK flooding proxy weight path for a given host.
     *
     * @param id Host UUID
     * @return /hosts/&lt;hostId&gt;/flooding_proxy_weight
     */
    public String getHostFloodingProxyWeightPath(UUID id) {
        return buildHostFloodingProxyWeightPath(id).toString();
    }

    private StringBuilder buildHostFloodingProxyWeightPath(UUID id) {
        return new StringBuilder(getHostPath(id)).append(FLOODING_PROXY_WEIGHT);
    }

    /**
     * Get ZK hosts path.
     *
     * @param hostId Host UUID
     * @return /hosts/&lt;hostId&gt;/interfaces
     */
    public String getHostInterfacesPath(UUID hostId) {
        return buildHostInterfacesPath(hostId).toString();
    }

    private StringBuilder buildHostInterfacesPath(UUID hostId) {
        return buildHostPath(hostId).append("/interfaces");
    }

    /**
     * Get ZK host interface path.
     *
     * @param hostId Host UUID
     * @param name   Host interface name
     * @return /hosts/&lt;hostId&gt;/interfaces/&lt;name&gt;
     */
    public String getHostInterfacePath(UUID hostId, String name) {
        return buildHostInterfacePath(hostId, name).toString();
    }

    private StringBuilder buildHostInterfacePath(UUID hostId, String name) {
        return buildHostPath(hostId).append("/interfaces/")
            .append(name);
    }

    public String getHostTunnelZonesPath(UUID hostId) {
        return buildHostTunnelZonesPath(hostId).toString();
    }

    private StringBuilder buildHostTunnelZonesPath(UUID hostId) {
        return buildHostPath(hostId).append("/").append(TUNNEL_ZONES);
    }

    public String getHostTunnelZonePath(UUID hostId, UUID zoneId) {
        return buildHostTunnelZonePath(hostId, zoneId).toString();
    }

    private StringBuilder buildHostTunnelZonePath(UUID hostId, UUID zoneId) {
        return buildHostTunnelZonesPath(hostId).append("/").append(zoneId);
    }

    public String getHostVrnMappingsPath(UUID hostId) {
        return buildHostVrnMappingsPath(hostId).toString();
    }

    private StringBuilder buildHostVrnMappingsPath(UUID hostId) {
        return buildHostPath(hostId).append("/vrnMappings");
    }

    public String getHostVrnPortMappingsPath(UUID hostIdentifier) {
        return buildHostVrnPortMappingsPath(hostIdentifier).toString();
    }

    private StringBuilder buildHostVrnPortMappingsPath(UUID hostIdentifier) {
        return buildHostVrnMappingsPath(hostIdentifier).append("/ports");
    }

    public String getHostVrnPortMappingPath(UUID hostIdentifier, UUID virtualPortId) {
        return buildHostVrnPortMappingPath(hostIdentifier,
                virtualPortId).toString();
    }

    private StringBuilder buildHostVrnPortMappingPath(UUID hostIdentifier, UUID virtualPortId) {
        return buildHostVrnPortMappingsPath(hostIdentifier).append("/")
            .append(virtualPortId);
    }

    public String getHostVrnDatapathMappingPath(UUID hostIdentifier) {
        return buildHostVrnDatapathMappingPath(hostIdentifier).toString();
    }

    private StringBuilder buildHostVrnDatapathMappingPath(UUID hostIdentifier) {
        return buildHostVrnMappingsPath(hostIdentifier).append("/datapath");
    }

    public String getTunnelZoneMembershipsPath(UUID zoneId) {
        return buildTunnelZoneMembershipsPath(zoneId).toString();
    }

    private StringBuilder buildTunnelZoneMembershipsPath(UUID zoneId) {
        return buildTunnelZonePath(zoneId).append("/").append(MEMBERSHIPS);
    }

    public String getTunnelZoneMembershipPath(UUID zoneId, UUID hostId) {
        return buildTunnelZoneMembershipPath(zoneId, hostId).toString();
    }

    private StringBuilder buildTunnelZoneMembershipPath(UUID zoneId, UUID hostId) {
        return buildTunnelZoneMembershipsPath(zoneId).append("/").append(hostId);
    }

    /**
     * Get ZK port groups path.
     *
     * @return /port_groups
     */
    public String getIpAddrGroupsPath() {
        return buildIpAddrGroupsPath().toString();
    }

    private StringBuilder buildIpAddrGroupsPath() {
        return basePath().append("/ip_addr_groups");
    }

    /**
     * Get ZK port group path.
     *
     * @param id Group UUID.
     * @return /port_groups/groupId
     */
    public String getIpAddrGroupPath(UUID id) {
        return buildIpAddrGroupPath(id).toString();
    }

    private StringBuilder buildIpAddrGroupPath(UUID id) {
        return new StringBuilder(getIpAddrGroupsPath()).append("/").append(id);
    }

    /**
     * Get ZK port group rules path.
     *
     * @param id Group UUID.
     * @return /port_groups/groupId/rules
     */
    public String getIpAddrGroupRulesPath(UUID id) {
        return buildIpAddrGroupRulesPath(id).toString();
    }

    private StringBuilder buildIpAddrGroupRulesPath(UUID id) {
        return buildIpAddrGroupPath(id).append("/rules");
    }

    /**
     * Get ZK port group rule path.
     *
     * @param id Group UUID.
     * @return /port_groups/groupId/rules/ruleId
     */
    public String getIpAddrGroupRulePath(UUID id, UUID ruleId) {
        return buildIpAddrGroupRulePath(id, ruleId).toString();
    }

    private StringBuilder buildIpAddrGroupRulePath(UUID id, UUID ruleId) {
        return buildIpAddrGroupRulesPath(id).append("/").append(ruleId);
    }

    public StringBuilder buildIpAddrGroupAddrsPath(UUID id) {
        return buildIpAddrGroupPath(id).append("/addrs");
    }

    public String getIpAddrGroupAddrsPath(UUID id) {
        return buildIpAddrGroupAddrsPath(id).toString();
    }

    public StringBuilder buildIpAddrGroupAddrPath(UUID id, String addr) {
        return buildIpAddrGroupAddrsPath(id).append("/").append(addr);
    }

    public String getIpAddrGroupAddrPath(UUID id, String addr) {
        return buildIpAddrGroupAddrPath(id, addr).toString();
    }

    private StringBuilder buildIpAddrGroupAddrPortsPath(UUID id, String addr) {
        return buildIpAddrGroupAddrPath(id, addr)
                .append("/ports");
    }

    public String getIpAddrGroupAddrPortsPath(UUID id, String addr) {
        return buildIpAddrGroupAddrPortsPath(id, addr).toString();
    }

    private StringBuilder buildIpAddrGroupAddrPortPath(UUID id, String addr,
                                                      UUID portId) {
        return buildIpAddrGroupAddrPortsPath(id, addr)
                .append("/")
                .append(portId.toString());
    }

    public String getIpAddrGroupAddrPortPath(UUID id, String addr,
                                             UUID portId) {
        return buildIpAddrGroupAddrPortPath(id, addr, portId).toString();
    }

    private StringBuilder buildLoadBalancersPath() {
        return basePath().append("/load_balancers");
    }

    public String getLoadBalancersPath() {
        return buildLoadBalancersPath().toString();
    }

    private StringBuilder buildLoadBalancerPath(UUID loadBalancerId){
        return buildLoadBalancersPath().append("/").append(loadBalancerId);
    }

    public String getLoadBalancerPath(UUID loadBalancerId) {
        return buildLoadBalancerPath(loadBalancerId).toString();
    }

    private StringBuilder buildLoadBalancerPoolsPath(UUID loadBalancerId) {
        return buildLoadBalancerPath(loadBalancerId).append("/pools");
    }

    public String getLoadBalancerPoolsPath(UUID loadBalancerId) {
        return buildLoadBalancerPoolsPath(loadBalancerId).toString();
    }

    private StringBuilder buildLoadBalancerVipsPath(UUID loadBalancerId){
        return buildLoadBalancerPath(loadBalancerId).append("/vips");
    }

    public String getLoadBalancerVipsPath(UUID loadBalancerId) {
        return buildLoadBalancerVipsPath(loadBalancerId).toString();
    }

    private StringBuilder buildLoadBalancerPoolPath(UUID id, UUID poolId) {
        return buildLoadBalancerPoolsPath(id).append("/").append(poolId);
    }

    public String getLoadBalancerPoolPath(UUID id, UUID poolId) {
        return buildLoadBalancerPoolPath(id, poolId).toString();
    }

    private StringBuilder buildLoadBalancerVipPath(UUID id, UUID vipId) {
        return buildLoadBalancerVipsPath(id).append("/").append(vipId);
    }

    public String getLoadBalancerVipPath(UUID id, UUID vipId) {
        return buildLoadBalancerVipPath(id, vipId).toString();
    }

    private StringBuilder buildHealthMonitorsPath() {
        return basePath().append("/health_monitors");
    }

    public String getHealthMonitorsPath() {
        return buildHealthMonitorsPath().toString();
    }

    private StringBuilder buildHealthMonitorPath(UUID healthMonitorId) {
        return buildHealthMonitorsPath().append("/").append(healthMonitorId);
    }

    public String getHealthMonitorPath(UUID healthMonitorId) {
        return buildHealthMonitorPath(healthMonitorId).toString();
    }

    private StringBuilder buildHealthMonitorPoolsPath(UUID healthMonitorId) {
        return buildHealthMonitorPath(healthMonitorId).append("/pools");
    }

    public String getHealthMonitorPoolsPath(UUID healthMonitorId) {
        return buildHealthMonitorPoolsPath(healthMonitorId).toString();
    }

    private StringBuilder buildHealthMonitorPoolPath(UUID id, UUID poolId) {
        return buildHealthMonitorPoolsPath(id).append("/").append(poolId);
    }

    public String getHealthMonitorPoolPath(UUID id, UUID poolId) {
        return buildHealthMonitorPoolPath(id, poolId).toString();
    }

    private StringBuilder buildPoolMembersPath() {
        return basePath().append("/pool_members");
    }

    private StringBuilder buildPoolMemberPath(UUID poolMemberId) {
        return buildPoolMembersPath().append("/").append(poolMemberId);
    }

    public String getPoolMemberPath(UUID poolMemberId) {
        return buildPoolMemberPath(poolMemberId).toString();
    }

    public String getPoolMembersPath() {
        return buildPoolMembersPath().toString();
    }

    private StringBuilder buildPoolsPath() {
        return basePath().append("/pools");
    }

    public String getPoolsPath() {
        return buildPoolsPath().toString();
    }

    private StringBuilder buildPoolPath(UUID poolId) {
        return buildPoolsPath().append("/").append(poolId);
    }

    public String getPoolPath(UUID poolId) {
        return buildPoolPath(poolId).toString();
    }

    private StringBuilder buildPoolMembersPath(UUID poolId) {
        return buildPoolPath(poolId).append("/pool_members");
    }

    public String getPoolMembersPath(UUID poolId) {
        return buildPoolMembersPath(poolId).toString();
    }

    private StringBuilder buildPoolMemberPath(UUID poolId, UUID poolMemberId) {
        return buildPoolMembersPath(poolId)
                .append("/").append(poolMemberId.toString());
    }

    public String getPoolMemberPath(UUID poolId, UUID memberId) {
        return buildPoolMemberPath(poolId, memberId).toString();
    }

    public StringBuilder buildPoolVipsPath(UUID poolId) {
        return buildPoolPath(poolId).append("/vips");
    }

    public String getPoolVipsPath(UUID poolId) {
        return buildPoolVipsPath(poolId).toString();
    }

    public StringBuilder buildPoolVipPath(UUID poolId, UUID vipId) {
        return buildPoolVipsPath(poolId).append("/").append(vipId);
    }

    public String getPoolVipPath(UUID poolId, UUID vipId) {
        return buildPoolVipPath(poolId, vipId).toString();
    }

    private StringBuilder buildVipsPath() {
        return basePath().append("/vips");
    }

    public String getVipsPath()  {
        return buildVipsPath().toString();
    }

    private StringBuilder buildVipPath(UUID vipId) {
        return buildVipsPath()
                .append("/").append(vipId.toString());
    }

    public String getVipPath(UUID vipId) {
        return buildVipPath(vipId).toString();
    }

    public StringBuilder buildPoolHealthMonitorMappingsPath() {
        return basePath().append("/pool_health_monitor_mappings");
    }

    public String getPoolHealthMonitorMappingsPath() {
        return buildPoolHealthMonitorMappingsPath().toString();
    }

    public StringBuilder buildPoolHealthMonitorMappingPath(
            UUID poolId, UUID healthMonitorId) {
        return buildPoolHealthMonitorMappingsPath().append("/").append(
                poolId).append("_").append(healthMonitorId);
    }

    public String getPoolHealthMonitorMappingsPath(UUID poolId,
                                                   UUID healthMonitorId) {
        return buildPoolHealthMonitorMappingPath(
                poolId, healthMonitorId).toString();
    }

    private StringBuilder buildVtepsPath() {
        return basePath().append("/vteps");
    }

    public String getVtepsPath() {
        return buildVtepsPath().toString();
    }

    private StringBuilder buildVtepPath(IPv4Addr ipAddr) {
        return buildVtepsPath().append("/").append(ipAddr);
    }

    public String getVtepPath(IPv4Addr ipAddr) {
        return buildVtepPath(ipAddr).toString();
    }

    private StringBuilder buildVtepBindingsPath(IPv4Addr ipAddr) {
        return buildVtepPath(ipAddr).append("/bindings");
    }

    public String getVtepBindingsPath(IPv4Addr ipAddr) {
        return buildVtepBindingsPath(ipAddr).toString();
    }

    public String getVtepOwnerPath(IPv4Addr ipAddr) {
        return buildVtepPath(ipAddr).append("/owner").toString();
    }

    private StringBuilder buildVtepBindingPath(IPv4Addr ipAddr, String portName,
                                               short vlanId, UUID networkId) {
        return buildVtepBindingsPath(ipAddr).append('/')
                .append(vlanId).append('_')
                .append(networkId).append('_')
                .append(encodePathSegment(portName));
    }

    public String getVtepBindingPath(IPv4Addr ipAddr, String portName,
                                     short vlanId, UUID networkId) {
        return buildVtepBindingPath(ipAddr, portName,
                                    vlanId, networkId).toString();
    }

    private StringBuilder buildVxLanPortIdsPath() {
        return basePath().append("/vxlan_port_ids");
    }

    public String getVxLanPortIdsPath() {
        return buildVxLanPortIdsPath().toString();
    }

    private StringBuilder buildVxLanPortIdPath(UUID id) {
        return buildVxLanPortIdsPath().append("/").append(id);
    }

    public String getVxLanPortIdPath(UUID id) {
        return buildVxLanPortIdPath(id).toString();
    }

    public String getVniCounterPath() {
        return basePath().append("/vniCounter").toString();
    }

    private StringBuilder buildLocksPath() {
        return basePath().append("/locks");
    }

    public String getLocksPath() {
        return buildLocksPath().toString();
    }

    public StringBuilder buildLockPath(String lockName) {
        return buildLocksPath().append("/").append(lockName);
    }

    public String getLockPath(String lockName) {
        return buildLockPath(lockName).toString();
    }

    // Neutron specific paths
    private StringBuilder buildNeutronPath() {
        return basePath().append("/neutron");
    }

    public String getNeutronPath() {
        return buildNeutronPath().toString();
    }

    public StringBuilder buildNeutronProviderRouterPath() {
        return buildNeutronPath().append("/provider_router");
    }

    public String getNeutronProviderRouterPath() {
        return buildNeutronProviderRouterPath().toString();
    }

    private StringBuilder buildNeutronNetworksPath() {
        return buildNeutronPath().append("/networks");
    }

    public String getNeutronNetworksPath() {
        return buildNeutronNetworksPath().toString();
    }

    private StringBuilder buildNeutronNetworkPath(UUID id) {
        return buildNeutronNetworksPath().append("/").append(id);
    }

    public String getNeutronNetworkPath(UUID id) {
        return buildNeutronNetworkPath(id).toString();
    }

    private StringBuilder buildNeutronSubnetsPath() {
        return buildNeutronPath().append("/subnets");
    }

    public String getNeutronSubnetsPath() {
        return buildNeutronSubnetsPath().toString();
    }

    private StringBuilder buildNeutronSubnetPath(UUID id) {
        return buildNeutronSubnetsPath().append("/").append(id);
    }

    public String getNeutronSubnetPath(UUID id) {
        return buildNeutronSubnetPath(id).toString();
    }

    private StringBuilder buildNeutronPortsPath() {
        return buildNeutronPath().append("/ports");
    }

    public String getNeutronPortsPath() {
        return buildNeutronPortsPath().toString();
    }

    private StringBuilder buildNeutronPortPath(UUID id) {
        return buildNeutronPortsPath().append("/").append(id);
    }

    public String getNeutronPortPath(UUID id) {
        return buildNeutronPortPath(id).toString();
    }

    private StringBuilder buildNeutronRoutersPath() {
        return buildNeutronPath().append("/routers");
    }

    public String getNeutronRoutersPath() {
        return buildNeutronRoutersPath().toString();
    }

    private StringBuilder buildNeutronRouterPath(UUID id) {
        return buildNeutronRoutersPath().append("/").append(id);
    }

    public String getNeutronRouterPath(UUID id) {
        return buildNeutronRouterPath(id).toString();
    }

    private StringBuilder buildNeutronFloatingIpsPath() {
        return buildNeutronPath().append("/floating_ips");
    }

    public String getNeutronFloatingIpsPath() {
        return buildNeutronFloatingIpsPath().toString();
    }

    private StringBuilder buildNeutronFloatingIpPath(UUID id) {
        return buildNeutronFloatingIpsPath().append("/").append(id);
    }

    public String getNeutronFloatingIpPath(UUID id) {
        return buildNeutronFloatingIpPath(id).toString();
    }

    private StringBuilder buildNeutronSecurityGroupsPath() {
        return buildNeutronPath().append("/security_groups");
    }

    public String getNeutronSecurityGroupsPath() {
        return buildNeutronSecurityGroupsPath().toString();
    }

    private StringBuilder buildNeutronSecurityGroupPath(UUID id) {
        return buildNeutronSecurityGroupsPath().append("/").append(id);
    }

    public String getNeutronSecurityGroupPath(UUID id) {
        return buildNeutronSecurityGroupPath(id).toString();
    }

    private StringBuilder buildNeutronSecurityGroupRulesPath() {
        return buildNeutronPath().append("/security_group_rules");
    }

    public String getNeutronSecurityGroupRulesPath() {
        return buildNeutronSecurityGroupRulesPath().toString();
    }

    private StringBuilder buildNeutronSecurityGroupRulePath(UUID id) {
        return buildNeutronSecurityGroupRulesPath().append("/").append(id);
    }

    public String getNeutronSecurityGroupRulePath(UUID id) {
        return buildNeutronSecurityGroupRulePath(id).toString();
    }

    // Neutron Load Balancer Paths
    private StringBuilder buildNeutronLoadBalancerPath() {
        return buildNeutronPath().append("/loadbalancer");
    }

    public String getNeutronLoadBalancerPath() {
        return buildNeutronLoadBalancerPath().toString();
    }

    private StringBuilder buildNeutronPoolsPath() {
        return buildNeutronLoadBalancerPath().append("/pools");
    }

    public String getNeutronPoolsPath() {
        return buildNeutronPoolsPath().toString();
    }

    private StringBuilder buildNeutronPoolPath(UUID id) {
        return buildNeutronPoolsPath().append("/").append(id);
    }

    public String getNeutronPoolPath(UUID id) {
        return buildNeutronPoolPath(id).toString();
    }

    private StringBuilder buildNeutronVipsPath() {
        return buildNeutronLoadBalancerPath().append("/vips");
    }

    public String getNeutronVipsPath() {
        return buildNeutronVipsPath().toString();
    }

    private StringBuilder buildNeutronVipPath(UUID id) {
        return buildNeutronVipsPath().append("/").append(id);
    }

    public String getNeutronVipPath(UUID id) {
        return buildNeutronVipPath(id).toString();
    }

    private StringBuilder buildNeutronMembersPath() {
        return buildNeutronLoadBalancerPath().append("/members");
    }

    public String getNeutronMembersPath() {
        return buildNeutronMembersPath().toString();
    }

    private StringBuilder buildNeutronMemberPath(UUID id) {
        return buildNeutronMembersPath().append("/").append(id);
    }

    public String getNeutronMemberPath(UUID id) {
        return buildNeutronMemberPath(id).toString();
    }

    private StringBuilder buildNeutronHealthMonitorsPath() {
        return buildNeutronLoadBalancerPath().append("/health_monitors");
    }

    public String getNeutronHealthMonitorsPath() {
        return buildNeutronHealthMonitorsPath().toString();
    }

    private StringBuilder buildNeutronHealthMonitorPath(UUID id) {
        return buildNeutronHealthMonitorsPath().append("/").append(id);
    }

    public String getNeutronHealthMonitorPath(UUID id) {
        return buildNeutronHealthMonitorPath(id).toString();
    }

    /**
     * URL-encode a path segment that may contain forward slashes.
     */
    public static String encodePathSegment(String segment) {
        try {
            return URLEncoder.encode(segment, UTF8);
        } catch (UnsupportedEncodingException ex) {
            // If UTF-8 isn't supported, the end times are upon us.
            throw new RuntimeException(ex);
        }
    }

    /**
     * Decode a path segment encoded with encodePathSegment().
     */
    public static String decodePathSegment(String encoded) {
        try {
            return URLDecoder.decode(encoded, UTF8);
        } catch (UnsupportedEncodingException ex) {
            // The world has come unmoored.
            throw new RuntimeException(ex);
        }
    }
}
