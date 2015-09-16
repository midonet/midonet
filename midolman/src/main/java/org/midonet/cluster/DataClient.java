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
package org.midonet.cluster;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.midonet.cluster.data.AdRoute;
import org.midonet.cluster.data.BGP;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Chain;
import org.midonet.cluster.data.HostVersion;
import org.midonet.cluster.data.IpAddrGroup;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.PortGroup;
import org.midonet.cluster.data.Route;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.SystemState;
import org.midonet.cluster.data.TraceRequest;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.cluster.data.WriteVersion;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.host.Interface;
import org.midonet.cluster.data.host.VirtualPortMapping;
import org.midonet.cluster.data.l4lb.HealthMonitor;
import org.midonet.cluster.data.l4lb.LoadBalancer;
import org.midonet.cluster.data.l4lb.Pool;
import org.midonet.cluster.data.l4lb.PoolMember;
import org.midonet.cluster.data.l4lb.VIP;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.VlanMacPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.Ip4ToMacReplicatedMap;
import org.midonet.midolman.state.MacPortMap;
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.IPv6Subnet;
import org.midonet.packets.MAC;

import static org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException;

public interface DataClient {

    /* BGP advertising routes related methods */
    @CheckForNull AdRoute adRoutesGet(UUID id)
            throws StateAccessException, SerializationException;

    List<AdRoute> adRoutesFindByBgp(UUID bgpId)
            throws StateAccessException, SerializationException;


    /* BGP related methods */
    void bgpSetStatus(@Nonnull UUID id, @Nonnull String status)
            throws StateAccessException, SerializationException;

    @CheckForNull BGP bgpGet(UUID id)
            throws StateAccessException, SerializationException;

    List<BGP> bgpFindByPort(UUID portId)
            throws StateAccessException, SerializationException;

    /* Bridges related methods */
    boolean bridgeExists(UUID id)
            throws StateAccessException;

    @CheckForNull Bridge bridgesGet(UUID id)
            throws StateAccessException, SerializationException;

    List<Bridge> bridgesGetAll() throws StateAccessException,
            SerializationException;

    List<Bridge> bridgesFindByTenant(String tenantId)
            throws StateAccessException, SerializationException;

    void ensureBridgeHasVlanDirectory(@Nonnull UUID bridgeId)
            throws StateAccessException;

    boolean bridgeHasMacTable(@Nonnull UUID bridgeId, short vlanId)
            throws StateAccessException;

    /**
     * Returns a MAC-port table for the specified bridge / VLAN IDs that are
     * automatically synchronized with the backend data store.
     * @param bridgeId A bridge ID.
     * @param vlanId A VLAN ID
     * @param ephemeral True if a MAC/port entry newly inserted to the table are
     * ephemeral entries, and false otherwise.
     * @return A MAC-port table that is synchronized with the backend datastore.
     * @throws StateAccessException
     */
    MacPortMap bridgeGetMacTable(
            @Nonnull UUID bridgeId, short vlanId, boolean ephemeral)
            throws StateAccessException;

    void bridgeAddMacPort(@Nonnull UUID bridgeId, short vlanId,
                          @Nonnull MAC mac, @Nonnull UUID portId)
        throws StateAccessException;

    boolean bridgeHasMacPort(@Nonnull UUID bridgeId, Short vlanId,
                             @Nonnull MAC mac, @Nonnull UUID portId)
        throws StateAccessException;

    List<VlanMacPort> bridgeGetMacPorts(@Nonnull UUID bridgeId)
        throws StateAccessException;

    List<VlanMacPort> bridgeGetMacPorts(@Nonnull UUID bridgeId, short vlanId)
        throws StateAccessException;

    void bridgeDeleteMacPort(@Nonnull UUID bridgeId, Short vlanId,
                             @Nonnull MAC mac, @Nonnull UUID portId)
        throws StateAccessException;

    /**
     * Creates or replaces an Ip->Mac mapping.
     * It sets a regular entry (if a learned one existed, it is replaced)
     */
    void bridgeAddIp4Mac(@Nonnull UUID bridgeId, @Nonnull IPv4Addr ip4,
                         @Nonnull MAC mac)
        throws StateAccessException;

    /**
     * Checks if a persistent or learned IP->MAC mapping exists.
     */
    boolean bridgeHasIP4MacPair(@Nonnull UUID bridgeId,
                                @Nonnull IPv4Addr ip, @Nonnull MAC mac)
        throws StateAccessException;

    /**
     * Gets all IP->MAC mappings ('hints' and regular mappings).
     */
    Map<IPv4Addr, MAC> bridgeGetIP4MacPairs(@Nonnull UUID bridgeId)
        throws StateAccessException;

    /**
     * Deletes an IP->MAC mapping (either learned or persistent).
     */
    void bridgeDeleteIp4Mac(@Nonnull UUID bridgeId, @Nonnull IPv4Addr ip4,
                            @Nonnull MAC mac)
        throws StateAccessException;

    /* Chains related methods */
    @CheckForNull Chain chainsGet(UUID id)
            throws StateAccessException, SerializationException;

    List<Chain> chainsGetAll() throws StateAccessException,
            SerializationException;

    List<Chain> chainsFindByTenant(String tenantId)
            throws StateAccessException, SerializationException;

    @CheckForNull Subnet dhcpSubnetsGet(UUID bridgeId, IPv4Subnet subnetAddr)
            throws StateAccessException, SerializationException;

    List<Subnet> dhcpSubnetsGetByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException;

    List<Subnet> dhcpSubnetsGetByBridgeEnabled(UUID bridgeId)
            throws StateAccessException, SerializationException;

    @CheckForNull org.midonet.cluster.data.dhcp.Host dhcpHostsGet(
            UUID bridgeId, IPv4Subnet subnet, String mac)
            throws StateAccessException, SerializationException;

    List<org.midonet.cluster.data.dhcp.Host> dhcpHostsGetBySubnet(
            UUID bridgeId, IPv4Subnet subnet)
            throws StateAccessException, SerializationException;

    @CheckForNull Subnet6 dhcpSubnet6Get(UUID bridgeId, IPv6Subnet prefix)
            throws StateAccessException, SerializationException;

    List<Subnet6> dhcpSubnet6sGetByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException;

    @CheckForNull V6Host dhcpV6HostGet(
            UUID bridgeId, IPv6Subnet prefix, String clientId)
            throws StateAccessException, SerializationException;

    List<V6Host> dhcpV6HostsGetByPrefix(
            UUID bridgeId, IPv6Subnet prefix)
            throws StateAccessException, SerializationException;

    boolean tunnelZonesExists(UUID uuid) throws StateAccessException;

    @CheckForNull TunnelZone tunnelZonesGet(UUID uuid)
            throws StateAccessException, SerializationException;

    List<TunnelZone> tunnelZonesGetAll()
            throws StateAccessException, SerializationException;

    Set<TunnelZone.HostConfig> tunnelZonesGetMemberships(UUID uuid)
        throws StateAccessException;

    @CheckForNull TunnelZone.HostConfig tunnelZonesGetMembership(
            UUID uuid, UUID hostId)
            throws StateAccessException, SerializationException;

    boolean tunnelZonesContainHost(UUID hostId)
            throws StateAccessException, SerializationException;

    /* load balancers related methods */

    @CheckForNull
    LoadBalancer loadBalancerGet(UUID id)
            throws StateAccessException, SerializationException;

    List<LoadBalancer> loadBalancersGetAll()
            throws StateAccessException, SerializationException;

    List<Pool> loadBalancerGetPools(UUID id)
            throws StateAccessException, SerializationException;

    List<VIP> loadBalancerGetVips(UUID id)
            throws StateAccessException, SerializationException;

    @CheckForNull
    HealthMonitor healthMonitorGet(UUID id)
            throws StateAccessException, SerializationException;

    List<HealthMonitor> healthMonitorsGetAll() throws StateAccessException,
            SerializationException;

    List<Pool> healthMonitorGetPools(UUID id)
            throws StateAccessException, SerializationException;

    /* pool member related methods */
    boolean poolMemberExists(UUID id) throws StateAccessException;

    @CheckForNull
    PoolMember poolMemberGet(UUID id)
            throws StateAccessException, SerializationException;

    List<PoolMember> poolMembersGetAll() throws StateAccessException,
            SerializationException;

    /* pool related methods */

    @CheckForNull
    Pool poolGet(UUID id)
            throws StateAccessException, SerializationException;

    List<Pool> poolsGetAll() throws StateAccessException,
            SerializationException;

    List<PoolMember> poolGetMembers(UUID id)
            throws StateAccessException, SerializationException;

    List<VIP> poolGetVips(UUID id)
            throws StateAccessException, SerializationException;

    void poolSetMapStatus(UUID id, PoolHealthMonitorMappingStatus status)
            throws StateAccessException, SerializationException;

    /* VIP related methods */

    @CheckForNull
    VIP vipGet(UUID id)
            throws StateAccessException, SerializationException;

    List<VIP> vipGetAll()
            throws StateAccessException, SerializationException;

    /* hosts related methods */
    @CheckForNull Host hostsGet(UUID hostId)
            throws StateAccessException, SerializationException;

    boolean hostsExists(UUID hostId) throws StateAccessException;

    boolean hostsIsAlive(UUID hostId) throws StateAccessException;

    boolean hostsHasPortBindings(UUID hostId) throws StateAccessException;

    List<Host> hostsGetAll()
            throws StateAccessException;

    List<Interface> interfacesGetByHost(UUID hostId)
            throws StateAccessException, SerializationException;

    @CheckForNull Interface interfacesGet(UUID hostId, String interfaceName)
            throws StateAccessException, SerializationException;

    List<VirtualPortMapping> hostsGetVirtualPortMappingsByHost(UUID hostId)
            throws StateAccessException, SerializationException;

    boolean hostsVirtualPortMappingExists(UUID hostId, UUID portId)
        throws StateAccessException;

    @CheckForNull VirtualPortMapping hostsGetVirtualPortMapping(
            UUID hostId, UUID portId)
            throws StateAccessException, SerializationException;

    /* Ports related methods */
    boolean portsExists(UUID id) throws StateAccessException;

    List<BridgePort> portsFindByBridge(UUID bridgeId) throws
            StateAccessException, SerializationException;

    List<Port<?, ?>> portsFindPeersByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException;

    List<Port<?, ?>> portsFindByRouter(UUID routerId) throws
            StateAccessException, SerializationException;

    List<Port<?, ?>> portsFindPeersByRouter(UUID routerId)
            throws StateAccessException, SerializationException;

    /**
     * Gets all ports in the topology.
     *
     * @return The list of the ports.
     * @throws StateAccessException An exception thrown when the ZooKeeper
     *                              access failed.
     * @throws SerializationException An exception thrown when the serialization
     *                                failed.
     */
    List<Port<?, ?>> portsGetAll()
            throws StateAccessException, SerializationException;

    @CheckForNull Port<?, ?> portsGet(UUID id)
            throws StateAccessException, SerializationException;

    List<Port<?, ?>> portsFindByPortGroup(UUID portGroupId)
            throws StateAccessException, SerializationException;

    /**
     * Gets the definition of an IP address group.
     *
     * @param id ID of IP address group to get.
     *
     * @return IP address group. Never null.
     *
     * @throws org.midonet.midolman.state.NoStatePathException
     *      If no IP address group with the specified ID exists
     */
    @CheckForNull IpAddrGroup ipAddrGroupsGet(UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Returns true if an IP Address group with the specified ID exists.
     */
    boolean ipAddrGroupsExists(UUID id) throws StateAccessException;

    /**
     * Get a list of all IP Address Groups.
     *
     * @return List of IPAddrGroup. May be empty, but never null.
     */
    List<IpAddrGroup> ipAddrGroupsGetAll() throws StateAccessException,
            SerializationException;

    /**
     * Checks an IP address group for the specified address.
     *
     * @param id IP address group's ID
     * @param addr IP address. May be IPv4 or IPv6. No canonicalization
     *             is performed, so only an address with an identical
     *             string representation will be found.
     *
     * @return True if the address is a member of the specified group.
     *
     * @throws org.midonet.midolman.state.NoStatePathException
     *      If no IP address group with the specified ID exists.
     */
    boolean ipAddrGroupHasAddr(UUID id, String addr)
            throws StateAccessException;

    /**
     * Gets all IP addresses in an IP address group.
     *
     * @param id IP address group ID.
     *
     * @return Set of all IP addresses in the specified IP address group.
     *         May be null, but never empty.
     *
     * @throws org.midonet.midolman.state.NoStatePathException
     *      If no IP address group with the specified ID exists.
     */
    Set<String> getAddrsByIpAddrGroup(UUID id)
            throws StateAccessException, SerializationException;

    /* Port group related methods */
    @CheckForNull PortGroup portGroupsGet(UUID id)
            throws StateAccessException, SerializationException;

    boolean portGroupsExists(UUID id) throws StateAccessException;

    List<PortGroup> portGroupsGetAll() throws StateAccessException,
            SerializationException;

    List<PortGroup> portGroupsFindByPort(UUID portId)
            throws StateAccessException, SerializationException;

    List<PortGroup> portGroupsFindByTenant(String tenantId)
            throws StateAccessException, SerializationException;

    boolean portGroupsIsPortMember(UUID id, UUID portId)
        throws StateAccessException;

    /* Routes related methods */
    @CheckForNull Route routesGet(UUID id)
            throws StateAccessException, SerializationException;

    void routesDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID routesCreate(@Nonnull Route route)
            throws StateAccessException, SerializationException;

    UUID routesCreateEphemeral(@Nonnull Route route)
            throws StateAccessException, SerializationException;

    List<Route> routesFindByRouter(UUID routerId)
            throws StateAccessException, SerializationException;


    /* Routers related methods */
    boolean routerExists(UUID id) throws StateAccessException;

    @CheckForNull Router routersGet(UUID id)
            throws StateAccessException, SerializationException;

    List<Router> routersGetAll() throws StateAccessException,
            SerializationException;

    List<Router> routersFindByTenant(String tenantId)
            throws StateAccessException, SerializationException;


    /* Rules related methods */
    @CheckForNull Rule<?, ?> rulesGet(UUID id)
            throws StateAccessException, SerializationException;

    void rulesDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID rulesCreate(@Nonnull Rule<?, ?> rule)
            throws StateAccessException, RuleIndexOutOfBoundsException,
            SerializationException;

    List<Rule<?, ?>> rulesFindByChain(UUID chainId)
            throws StateAccessException, SerializationException;

    /**
     * Get tenants
     *
     * @return Set of tenant IDs
     * @throws StateAccessException
     */
    Set<String> tenantsGetAll() throws StateAccessException;

    /**
     * Get the current write version.
     *
     * @return current write version.
     */
    WriteVersion writeVersionGet() throws StateAccessException;

    /**
     * Get the system state
     *
     * @return system state info
     * @throws StateAccessException
     */
    SystemState systemStateGet() throws StateAccessException;

    /**
     * Get the version info for all the hosts.
     *
     * @return A list of items containing the host version info
     * @throws StateAccessException
     */
    List<HostVersion> hostVersionsGet()
            throws StateAccessException;

    /* Trace request methods */
    @CheckForNull
    TraceRequest traceRequestGet(UUID id)
            throws StateAccessException, SerializationException;

    List<TraceRequest> traceRequestGetAll()
            throws StateAccessException, SerializationException;

    List<TraceRequest> traceRequestFindByTenant(String tenantId)
            throws StateAccessException, SerializationException;

    /**
     * Get the node id that is in line right before the given myNode for
     * health monitoring. Returns null if there is none. This is used
     * in health monitor leader election.
     *
     * @return The id of the node in front
     * @param myNode the node
     * @throws StateAccessException
     */
    Integer getPrecedingHealthMonitorLeader(Integer myNode)
            throws StateAccessException;

    VTEP vtepGet(IPv4Addr ipAddr)
            throws StateAccessException, SerializationException;

    List<VTEP> vtepsGetAll()
            throws StateAccessException, SerializationException;

    /**
     * Returns a list containing all the bindings configured in the given VTEP,
     * by fetching them from the storage (not the VTEP itself).
     *
     * @param ipAddr the management IP that identifies the VTEP.
     * @return a list that is never null
     * @throws StateAccessException
     */
    List<VtepBinding> vtepGetBindings(@Nonnull IPv4Addr ipAddr)
            throws StateAccessException;

    VtepBinding vtepGetBinding(@Nonnull IPv4Addr ipAddr,
                                      @Nonnull String portName, short vlanId)
            throws StateAccessException;

    /**
     * Get all the vtep bindings for this bridge and vtep.
     */
    List<VtepBinding> bridgeGetVtepBindings(@Nonnull UUID id,
                                                   IPv4Addr mgmtIp)
        throws StateAccessException, SerializationException;

    void vxLanPortIdsAsyncGet(DirectoryCallback<Set<UUID>> callback,
                                     Directory.TypedWatcher watcher)
        throws StateAccessException;

    Ip4ToMacReplicatedMap getIp4MacMap(UUID bridgeId)
        throws StateAccessException;
}
