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
import java.util.UUID;

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

    public ZkPathManager(String basePath) {
        setBasePath(basePath);
    }

    private StringBuilder basePath() {
        return new StringBuilder(basePath);
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
        if (this.basePath == null) {
            this.basePath = "";
        }
    }

    public String getTraceRequestsPath() {
        return basePath().append("/traces").toString();
    }

    public String getTraceRequestPath(UUID uuid) {
        return getTraceRequestsPath() + "/" + uuid;
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

    /**
     * Get write-version path.
     *
     * @return /write_version
     */
    public String getWriteVersionPath() {
        return basePath().append("/write_version").toString();
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
            .append(subnetAddr.toUriString());
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
            .append(prefix.toUriString());
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

    protected StringBuilder buildBridgesPath() {
        return basePath().append("/bridges");
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

    public StringBuilder buildIpAddrGroupAddrsPath(UUID id) {
        return buildIpAddrGroupPath(id).append("/addrs");
    }

    public String getIpAddrGroupAddrsPath(UUID id) {
        return buildIpAddrGroupAddrsPath(id).toString();
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

    private StringBuilder buildPoolMembersPath() {
        return basePath().append("/pool_members");
    }

    private StringBuilder buildPoolMemberPath(UUID poolMemberId) {
        return buildPoolMembersPath().append("/").append(poolMemberId);
    }

    public String getPoolMemberPath(UUID poolMemberId) {
        return buildPoolMemberPath(poolMemberId).toString();
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

    private StringBuilder buildLocksPath() {
        return basePath().append("/locks");
    }

    public StringBuilder buildLockPath(String lockName) {
        return buildLocksPath().append("/").append(lockName);
    }

    public String getLockPath(String lockName) {
        return buildLockPath(lockName).toString();
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
