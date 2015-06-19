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

import org.apache.zookeeper.Watcher;

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
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.Ip4ToMacReplicatedMap;
import org.midonet.midolman.state.MacPortMap;
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkLeaderElectionWatcher;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.MappingStatusException;
import org.midonet.midolman.state.l4lb.MappingViolationException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.VtepZkManager;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.IPv6Subnet;
import org.midonet.packets.MAC;

import static org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException;

public interface DataClient {

    /* BGP advertising routes related methods */
    @CheckForNull AdRoute adRoutesGet(UUID id)
            throws StateAccessException, SerializationException;

    void adRoutesDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID adRoutesCreate(@Nonnull AdRoute adRoute)
            throws StateAccessException, SerializationException;

    List<AdRoute> adRoutesFindByBgp(UUID bgpId)
            throws StateAccessException, SerializationException;


    /* BGP related methods */
    void bgpSetStatus(@Nonnull UUID id, @Nonnull String status)
            throws StateAccessException, SerializationException;

    @CheckForNull BGP bgpGet(UUID id)
            throws StateAccessException, SerializationException;

    void bgpDelete(UUID id) throws StateAccessException, SerializationException;

    UUID bgpCreate(@Nonnull BGP bgp)
            throws StateAccessException, SerializationException;

    List<BGP> bgpFindByPort(UUID portId)
            throws StateAccessException, SerializationException;

    /* Bridges related methods */
    boolean bridgeExists(UUID id)
            throws StateAccessException;

    @CheckForNull Bridge bridgesGet(UUID id)
            throws StateAccessException, SerializationException;

    void bridgesDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID bridgesCreate(@Nonnull Bridge bridge)
            throws StateAccessException, SerializationException;

    void bridgesUpdate(@Nonnull Bridge bridge)
            throws StateAccessException, SerializationException;

    List<Bridge> bridgesGetAll() throws StateAccessException,
            SerializationException;

    /**
     * Provide the ids of all the bridges with a binding to the given VTEP.
     */
    Set<UUID> bridgesBoundToVtep(IPv4Addr mgmtIp)
        throws StateAccessException, SerializationException;
    /**
     * Get an entity monitor for individual bridges
     */
    EntityMonitor<UUID, BridgeZkManager.BridgeConfig, Bridge> bridgesGetMonitor(
        ZookeeperConnectionWatcher zkConnection);

    /**
     * Get an entity monitor for the set of bridges
     */
    public EntityIdSetMonitor<UUID> bridgesGetUuidSetMonitor(
        ZookeeperConnectionWatcher zkConnection) throws StateAccessException;

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
     * Sets or replaces a learned Ip->Mac mapping, unless a persistent pair
     * for the same IP already existed..
     */
    void bridgeAddLearnedIp4Mac(@Nonnull UUID bridgeId, @Nonnull IPv4Addr ip4,
                                @Nonnull MAC mac)
        throws StateAccessException;

    /**
     * Checks if a persistent or learned IP->MAC mapping exists.
     */
    boolean bridgeHasIP4MacPair(@Nonnull UUID bridgeId,
                                @Nonnull IPv4Addr ip, @Nonnull MAC mac)
        throws StateAccessException;

    /**
     * Checks if a learned IP->MAC mapping exists.
     */
    boolean bridgeCheckLearnedIP4MacPair(@Nonnull UUID bridgeId,
                                         @Nonnull IPv4Addr ip,
                                         @Nonnull MAC mac)
        throws StateAccessException;

    /**
     * Checks if a persistent IP->MAC mapping exists.
     */
    boolean bridgeCheckPersistentIP4MacPair(@Nonnull UUID bridgeId,
                                            @Nonnull IPv4Addr ip,
                                            @Nonnull MAC mac)
        throws StateAccessException;

    /**
     * Returns the mac associated to the given ip, or null, if not pairing
     * for that ip is known.
     */
    MAC bridgeGetIp4Mac(@Nonnull UUID bridgeId, @Nonnull IPv4Addr ip)
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

    /**
     * Deletes a learned IP->MAC mapping.
     * It silently does nothing if the entry is persistent or does not exist.
     */
    void bridgeDeleteLearnedIp4Mac(@Nonnull UUID bridgeId,
                                   @Nonnull IPv4Addr ip4,
                                   @Nonnull MAC mac)
        throws StateAccessException;

    /**
     * Get the set of IP addresses associated to a given MAC in the
     * bridge's ARP table.
     * The resulting set can be empty.
     */
    Set<IPv4Addr> bridgeGetIp4ByMac(@Nonnull UUID bridgeId, @Nonnull MAC mac)
        throws StateAccessException;

    /**
     * Get a replicated map representing the ARP table for a given bridge.
     * This is mainly used to set watchers; manipulation of the table
     * should be done via the bridge*Ip4Mac* methods.
     */
    Ip4ToMacReplicatedMap bridgeGetArpTable(@Nonnull java.util.UUID bridgeId)
        throws StateAccessException;

    /* Chains related methods */
    @CheckForNull Chain chainsGet(UUID id)
            throws StateAccessException, SerializationException;

    void chainsDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID chainsCreate(@Nonnull Chain chain)
            throws StateAccessException, SerializationException;

    List<Chain> chainsGetAll() throws StateAccessException,
            SerializationException;

    List<Chain> chainsFindByTenant(String tenantId)
            throws StateAccessException, SerializationException;


    /* DHCP related methods */
    void dhcpSubnetsCreate(@Nonnull UUID bridgeId, @Nonnull Subnet subnet)
            throws StateAccessException, SerializationException;

    void dhcpSubnetsUpdate(@Nonnull UUID bridgeId, @Nonnull Subnet subnet)
            throws StateAccessException, SerializationException;

    void dhcpSubnetsDelete(UUID bridgeId, IPv4Subnet subnetAddr)
        throws StateAccessException;

    @CheckForNull Subnet dhcpSubnetsGet(UUID bridgeId, IPv4Subnet subnetAddr)
            throws StateAccessException, SerializationException;

    List<Subnet> dhcpSubnetsGetByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException;

    List<Subnet> dhcpSubnetsGetByBridgeEnabled(UUID bridgeId)
            throws StateAccessException, SerializationException;

    void dhcpHostsCreate(@Nonnull UUID bridgeId, @Nonnull IPv4Subnet subnet,
                         org.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException, SerializationException;

    void dhcpHostsUpdate(@Nonnull UUID bridgeId, @Nonnull IPv4Subnet subnet,
                         org.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException, SerializationException;

    @CheckForNull org.midonet.cluster.data.dhcp.Host dhcpHostsGet(
            UUID bridgeId, IPv4Subnet subnet, String mac)
            throws StateAccessException, SerializationException;

    void dhcpHostsDelete(UUID bridgId, IPv4Subnet subnet, String mac)
            throws StateAccessException;

    List<org.midonet.cluster.data.dhcp.Host> dhcpHostsGetBySubnet(
            UUID bridgeId, IPv4Subnet subnet)
            throws StateAccessException, SerializationException;

    /* DHCPV6 related methods */
    void dhcpSubnet6Create(@Nonnull UUID bridgeId, @Nonnull Subnet6 subnet)
            throws StateAccessException, SerializationException;

    void dhcpSubnet6Update(@Nonnull UUID bridgeId, @Nonnull Subnet6 subnet)
            throws StateAccessException, SerializationException;

    void dhcpSubnet6Delete(UUID bridgeId, IPv6Subnet prefix)
        throws StateAccessException;

    @CheckForNull Subnet6 dhcpSubnet6Get(UUID bridgeId, IPv6Subnet prefix)
            throws StateAccessException, SerializationException;

    List<Subnet6> dhcpSubnet6sGetByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException;

    void dhcpV6HostCreate(@Nonnull UUID bridgeId,
                          @Nonnull IPv6Subnet prefix,
                          V6Host host)
            throws StateAccessException, SerializationException;

    void dhcpV6HostUpdate(@Nonnull UUID bridgeId,
                          @Nonnull IPv6Subnet prefix,
                          V6Host host)
            throws StateAccessException, SerializationException;

    @CheckForNull V6Host dhcpV6HostGet(
            UUID bridgeId, IPv6Subnet prefix, String clientId)
            throws StateAccessException, SerializationException;

    void dhcpV6HostDelete(UUID bridgId, IPv6Subnet prefix, String clientId)
            throws StateAccessException;

    List<V6Host> dhcpV6HostsGetByPrefix(
            UUID bridgeId, IPv6Subnet prefix)
            throws StateAccessException, SerializationException;

    UUID tunnelZonesCreate(@Nonnull TunnelZone zone)
            throws StateAccessException, SerializationException;

    void tunnelZonesDelete(UUID uuid)
        throws StateAccessException;

    boolean tunnelZonesExists(UUID uuid) throws StateAccessException;

    @CheckForNull TunnelZone tunnelZonesGet(UUID uuid)
            throws StateAccessException, SerializationException;

    List<TunnelZone> tunnelZonesGetAll()
            throws StateAccessException, SerializationException;

    void tunnelZonesUpdate(@Nonnull TunnelZone zone)
            throws StateAccessException, SerializationException;

    Set<TunnelZone.HostConfig> tunnelZonesGetMemberships(UUID uuid)
        throws StateAccessException;

    @CheckForNull TunnelZone.HostConfig tunnelZonesGetMembership(
            UUID uuid, UUID hostId)
            throws StateAccessException, SerializationException;

    boolean tunnelZonesContainHost(UUID hostId)
            throws StateAccessException, SerializationException;

    UUID tunnelZonesAddMembership(
            @Nonnull UUID zoneId,
            @Nonnull TunnelZone.HostConfig hostConfig)
            throws StateAccessException, SerializationException;

    void tunnelZonesDeleteMembership(UUID zoneId, UUID membershipId)
        throws StateAccessException;

    EntityMonitor<UUID, TunnelZone.Data, TunnelZone> tunnelZonesGetMonitor(
            ZookeeperConnectionWatcher zkConnection);

    EntityIdSetMonitor<UUID> tunnelZonesGetUuidSetMonitor(
        ZookeeperConnectionWatcher zkConnection) throws StateAccessException;

    EntityIdSetMonitor<UUID> tunnelZonesGetMembershipsMonitor(
            @Nonnull UUID zoneId,
            ZookeeperConnectionWatcher zkConnection) throws StateAccessException;

    UUID hostsCreate(@Nonnull UUID id, @Nonnull Host host)
            throws StateAccessException, SerializationException;

    /* load balancers related methods */

    @CheckForNull
    LoadBalancer loadBalancerGet(UUID id)
            throws StateAccessException, SerializationException;

    void loadBalancerDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID loadBalancerCreate(@Nonnull LoadBalancer loadBalancer)
            throws StateAccessException, SerializationException,
            InvalidStateOperationException;

    void loadBalancerUpdate(@Nonnull LoadBalancer loadBalancer)
            throws StateAccessException, SerializationException,
            InvalidStateOperationException;

    List<LoadBalancer> loadBalancersGetAll()
            throws StateAccessException, SerializationException;

    List<Pool> loadBalancerGetPools(UUID id)
            throws StateAccessException, SerializationException;

    List<VIP> loadBalancerGetVips(UUID id)
            throws StateAccessException, SerializationException;

    @CheckForNull
    HealthMonitor healthMonitorGet(UUID id)
            throws StateAccessException, SerializationException;

    void healthMonitorDelete(UUID id)
            throws MappingStatusException, StateAccessException,
            SerializationException;

    UUID healthMonitorCreate(@Nonnull HealthMonitor healthMonitor)
            throws StateAccessException, SerializationException;

    void healthMonitorUpdate(@Nonnull HealthMonitor healthMonitor)
            throws MappingStatusException, StateAccessException,
            SerializationException;

    List<HealthMonitor> healthMonitorsGetAll() throws StateAccessException,
            SerializationException;

    List<Pool> healthMonitorGetPools(UUID id)
            throws StateAccessException, SerializationException;

    /* pool member related methods */
    boolean poolMemberExists(UUID id) throws StateAccessException;

    @CheckForNull
    PoolMember poolMemberGet(UUID id)
            throws StateAccessException, SerializationException;

    void poolMemberDelete(UUID id)
            throws MappingStatusException, StateAccessException,
            SerializationException;

    UUID poolMemberCreate(@Nonnull PoolMember poolMember)
            throws MappingStatusException, StateAccessException,
            SerializationException;

    void poolMemberUpdate(@Nonnull PoolMember poolMember)
            throws MappingStatusException, StateAccessException,
            SerializationException;

    void poolMemberUpdateStatus(UUID poolMemberId, LBStatus status)
            throws StateAccessException, SerializationException;

    List<PoolMember> poolMembersGetAll() throws StateAccessException,
            SerializationException;

    /* pool related methods */

    @CheckForNull
    Pool poolGet(UUID id)
            throws StateAccessException, SerializationException;

    void poolDelete(UUID id)
            throws MappingStatusException, StateAccessException,
            SerializationException;

    UUID poolCreate(@Nonnull Pool pool)
            throws MappingStatusException, StateAccessException,
            SerializationException;

    void poolUpdate(@Nonnull Pool pool)
            throws MappingStatusException, MappingViolationException,
            SerializationException, StateAccessException;

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

    void vipDelete(UUID id)
            throws MappingStatusException, StateAccessException,
            SerializationException;

    UUID vipCreate(@Nonnull VIP vip)
            throws MappingStatusException, StateAccessException,
            SerializationException;

    void vipUpdate(@Nonnull VIP vip)
            throws MappingStatusException, StateAccessException,
            SerializationException;

    List<VIP> vipGetAll()
            throws StateAccessException, SerializationException;

    /* hosts related methods */
    @CheckForNull Host hostsGet(UUID hostId)
            throws StateAccessException, SerializationException;

    void hostsDelete(UUID hostId) throws StateAccessException;

    boolean hostsExists(UUID hostId) throws StateAccessException;

    boolean hostsIsAlive(UUID hostId) throws StateAccessException;

    boolean hostsIsAlive(UUID hostId, Watcher watcher)
        throws StateAccessException;

    boolean hostsHasPortBindings(UUID hostId) throws StateAccessException;

    List<Host> hostsGetAll()
            throws StateAccessException;

    /**
     * Get an entity monitor for individual hosts
     */
    EntityMonitor<UUID, HostDirectory.Metadata, Host> hostsGetMonitor(
        ZookeeperConnectionWatcher zkConnection);

    /**
     * Get an entity monitor for the set of hosts
     */
    EntityIdSetMonitor<UUID> hostsGetUuidSetMonitor(
        ZookeeperConnectionWatcher zkConnection) throws StateAccessException;

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

    void hostsAddVrnPortMapping(@Nonnull UUID hostId, @Nonnull UUID portId,
                                @Nonnull String localPortName)
            throws StateAccessException, SerializationException;

    Port<?,?> hostsAddVrnPortMappingAndReturnPort(
            @Nonnull UUID hostId, @Nonnull UUID portId,
            @Nonnull String localPortName)
            throws StateAccessException, SerializationException;

    void hostsAddDatapathMapping(
            @Nonnull UUID hostId, @Nonnull String datapathName)
            throws StateAccessException, SerializationException;

    void hostsDelVrnPortMapping(UUID hostId, UUID portId)
            throws StateAccessException, SerializationException;

    /**
     * Gets the flooding proxy weight value for the host, and sets up
     * a watcher on the flooding proxy weight path. The method returns null, and
     * does not set any watcher if the host node does not exist.
     *
     * @param hostId The host identifier.
     * @param watcher The watcher.
     * @return The flooding proxy weight or null if the host or the weight
     *         ZooKeeper node does not exist.
     */
    Integer hostsGetFloodingProxyWeight(UUID hostId, Watcher watcher)
            throws StateAccessException, SerializationException;

    void hostsSetFloodingProxyWeight(UUID hostId, int weight)
            throws StateAccessException, SerializationException;

    /* Ports related methods */
    boolean portsExists(UUID id) throws StateAccessException;

    UUID portsCreate(@Nonnull Port<?, ?> port)
            throws StateAccessException, SerializationException;

    void portsDelete(UUID id)
            throws StateAccessException, SerializationException;

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

    void portsUpdate(@Nonnull Port<?, ?> port)
            throws StateAccessException, SerializationException;

    void portsLink(@Nonnull UUID portId, @Nonnull UUID peerPortId)
            throws StateAccessException, SerializationException;

    void portsUnlink(@Nonnull UUID portId)
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
     * Deletes an IP address group.
     *
     * @param id ID of IP address group to delete.
     *
     * @throws org.midonet.midolman.state.NoStatePathException
     *      If no IP address group with the specified ID exists
     */
    void ipAddrGroupsDelete(UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Creates an IP address group.
     *
     * @param ipAddrGroup
     *      IP address group information. If the id field is initialized,
     *      that will be the ID of the newly created address group. Otherwise,
     *      a random UUID will be assigned.
     *
     * @return ID of the newly created IP address group.
     *
     * @throws org.midonet.midolman.state.StatePathExistsException
     *      If an IP address group with the specified ID already exists.
     */
    UUID ipAddrGroupsCreate(@Nonnull IpAddrGroup ipAddrGroup)
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
     * Adds an IP address to an IP address group. Idempotent.
     *
     * @param id IP address group's ID.
     * @param addr IP address. May be IPv4 or IPv6.
     */
    void ipAddrGroupAddAddr(@Nonnull UUID id, @Nonnull String addr)
            throws StateAccessException, SerializationException;

    /**
     * Removes an IP address from an IP address group. Idempotent.
     *
     * @param id IP address group's ID
     * @param addr IP address. May be IPv4 or IPv6. No canonicalization
     *             is performed, so only an address with an identical
     *             string representation will be removed.
     */
    void ipAddrGroupRemoveAddr(UUID id, String addr)
            throws StateAccessException, SerializationException;

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

    void portGroupsDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID portGroupsCreate(@Nonnull PortGroup portGroup)
            throws StateAccessException, SerializationException;

    void portGroupsUpdate(@Nonnull PortGroup portGroup)
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

    void portGroupsAddPortMembership(@Nonnull UUID id, @Nonnull UUID portId)
            throws StateAccessException, SerializationException;

    void portGroupsRemovePortMembership(UUID id, UUID portId)
            throws StateAccessException, SerializationException;


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

    void routersDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID routersCreate(@Nonnull Router router)
            throws StateAccessException, SerializationException;

    void routersUpdate(@Nonnull Router router)
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
    public WriteVersion writeVersionGet() throws StateAccessException;

    /**
     * Overwrites the current write version with the string supplied
     * @param newVersion The new version to set the write version to.
     */
    public void writeVersionUpdate(WriteVersion newVersion)
            throws StateAccessException;

    /**
     * Get the system state
     *
     * @return system state info
     * @throws StateAccessException
     */
    public SystemState systemStateGet() throws StateAccessException;

    /**
     * Update the system state
     *
     * @param systemState the new system state
     * @throws StateAccessException
     */
    public void systemStateUpdate(SystemState systemState)
        throws StateAccessException;

    /**
     * Get the version info for all the hosts.
     *
     * @return A list of items containing the host version info
     * @throws StateAccessException
     */
    public List<HostVersion> hostVersionsGet()
            throws StateAccessException;

    /* Trace request methods */
    @CheckForNull
    TraceRequest traceRequestGet(UUID id)
            throws StateAccessException, SerializationException;

    void traceRequestDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID traceRequestCreate(@Nonnull TraceRequest request)
            throws StateAccessException, SerializationException,
            RuleIndexOutOfBoundsException;

    UUID traceRequestCreate(@Nonnull TraceRequest request, boolean enabled)
            throws StateAccessException, SerializationException,
            RuleIndexOutOfBoundsException;

    List<TraceRequest> traceRequestGetAll()
            throws StateAccessException, SerializationException;

    List<TraceRequest> traceRequestFindByTenant(String tenantId)
            throws StateAccessException, SerializationException;

    void traceRequestEnable(UUID id)
            throws StateAccessException, SerializationException,
            RuleIndexOutOfBoundsException;

    void traceRequestDisable(UUID id)
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
    public Integer getPrecedingHealthMonitorLeader(Integer myNode)
            throws StateAccessException;

    /**
     * register as a health monitor capable node.
     *
     * @param cb the callback that will be executed upon becoming leader.
     * @return The id assigned to this node on registering.
     * @throws StateAccessException
     */
    public Integer registerAsHealthMonitorNode(
            ZkLeaderElectionWatcher.ExecuteOnBecomingLeader cb)
            throws StateAccessException;

    /**
     * Remove the registration node for health monitor leader election.
     * Basically useless for anything but testing.
     *
     * @param node node to remove
     * @throws StateAccessException
     */
    public void removeHealthMonitorLeaderNode(Integer node)
            throws StateAccessException;

    public void vtepCreate(VTEP vtep)
            throws StateAccessException, SerializationException;

    public VTEP vtepGet(IPv4Addr ipAddr)
            throws StateAccessException, SerializationException;

    public List<VTEP> vtepsGetAll()
            throws StateAccessException, SerializationException;

    /**
     * Deletes a VTEP. Will fail if the VTEP has bindings.
     *
     * @param ipAddr IP address of VTEP to delete.
     *
     * @throws org.midonet.midolman.state.NodeNotEmptyStateException
     *         If the VTEP still has bindings.
     *
     * @throws org.midonet.midolman.state.NoStatePathException
     *         If the VTEP does not exist.
     */
    public void vtepDelete(IPv4Addr ipAddr)
            throws StateAccessException, SerializationException;

    public void vtepUpdate(VTEP vtep)
            throws StateAccessException, SerializationException;

    public void vtepAddBinding(@Nonnull IPv4Addr ipAddr,
                               @Nonnull String portName, short vlanId,
                               @Nonnull UUID networkId)
            throws StateAccessException;

    public void vtepDeleteBinding(@Nonnull IPv4Addr ipAddr,
                                  @Nonnull String portName, short vlanId)
            throws StateAccessException;

    /**
     * Returns a list containing all the bindings configured in the given VTEP,
     * by fetching them from the storage (not the VTEP itself).
     *
     * @param ipAddr the management IP that identifies the VTEP.
     * @return a list that is never null
     * @throws StateAccessException
     */
    public List<VtepBinding> vtepGetBindings(@Nonnull IPv4Addr ipAddr)
            throws StateAccessException;

    public VtepBinding vtepGetBinding(@Nonnull IPv4Addr ipAddr,
                                      @Nonnull String portName, short vlanId)
            throws StateAccessException;

    /**
     * Get an entity monitor for individual VTEPs
     */
    EntityMonitor<IPv4Addr, VtepZkManager.VtepConfig, VTEP> vtepsGetMonitor(
        ZookeeperConnectionWatcher zkConnection);

    /**
     * Get an entity monitor for the set of VTEPs
     */
    EntityIdSetMonitor<IPv4Addr> vtepsGetAllSetMonitor(
        ZookeeperConnectionWatcher zkConnection) throws StateAccessException;

    /**
     * Generates and returns a new VNI for VTEP logical switch creation.
     * Successive calls will return monotonically increasing values.
     */
    public int getNewVni() throws StateAccessException;

    /**
     * Get all the vtep bindings for this bridge and any VTEP.
     */
    public List<VtepBinding> bridgeGetVtepBindings(@Nonnull UUID id)
        throws StateAccessException, SerializationException;

    /**
     * Get all the vtep bindings for this bridge and vtep.
     */
    public List<VtepBinding> bridgeGetVtepBindings(@Nonnull UUID id,
                                                   IPv4Addr mgmtIp)
        throws StateAccessException, SerializationException;

    public VxLanPort bridgeCreateVxLanPort(
            UUID bridgeId, IPv4Addr mgmtIp, int mgmtPort, int vni,
            IPv4Addr tunnelIp, UUID tunnelZoneId)
            throws StateAccessException, SerializationException;

    /**
     * Deletes the VXLAN port associated to the given VTEP. Does not delete
     * bindings on the VTEP since the DataClient is not VTEP-aware. This will be
     * done by the VxlanGateway service.
     */
    public void bridgeDeleteVxLanPort(UUID bridgeId, IPv4Addr vxLanPort)
            throws SerializationException, StateAccessException;


    public void bridgeDeleteVxLanPort(VxLanPort port)
        throws SerializationException, StateAccessException;

    /**
     * Tries to take ownership of the given VTEP.
     *
     * @param mgmtIp The management IP of the VTEP
     * @param ownerId The ID of the node trying to take ownership of the VTEP
     * @return The ID of the node that owns the VTEP, never null
     */
    public UUID tryOwnVtep(IPv4Addr mgmtIp, UUID ownerId)
        throws SerializationException, StateAccessException;

    /**
     * Tries to take ownership of the given VTEP.
     *
     * @param mgmtIp The management IP of the VTEP
     * @param ownerId The ID of the node trying to take ownership of the VTEP
     * @param watcher An watcher to install on the ownership node of the VTEP,
     *                regardless of whether ownership is taken or not. The
     *                watcher will notify of the changes to the ownership node.
     * @return The ID of the node that owns the VTEP, never null.
     */
    public UUID tryOwnVtep(IPv4Addr mgmtIp, UUID ownerId, Watcher watcher)
        throws SerializationException, StateAccessException;

    /**
     * Deletes the ownership for the given VTEP. The method deletes the
     * ownership only if the specified identifier currently owns the VTEP.
     *
     * @param mgmtIp The management IP of the VTEP
     * @param ownerId The ID of the node deleting the VTEP ownership.
     * @return True if the ownership was deleted, false otherwise.
     */
    public boolean deleteVtepOwner(IPv4Addr mgmtIp, UUID ownerId)
        throws SerializationException, StateAccessException;

    /**
     * See vxlanTunnelEndpointFor(BridgePort port)
     * @return null if the bridge port id does not correspond to a bridge
     * currently connected to a vtep.
     */
    public IPv4Addr vxlanTunnelEndpointFor(UUID bridgePortId)
        throws SerializationException, StateAccessException;

    /**
     * Register a watcher for the given port.
     * This is mainly intended to track vxLanPort removals.
     * @return true if the port exists, false otherwise.
     */
    public boolean portWatch(UUID portId, Directory.TypedWatcher watcher)
        throws StateAccessException, SerializationException;

    public Bridge bridgeGetAndWatch(UUID id, Directory.TypedWatcher watcher)
        throws StateAccessException, SerializationException;

    public void vxLanPortIdsAsyncGet(DirectoryCallback<Set<UUID>> callback,
                                     Directory.TypedWatcher watcher)
        throws StateAccessException;

    public Ip4ToMacReplicatedMap getIp4MacMap(UUID bridgeId)
        throws StateAccessException;
}
