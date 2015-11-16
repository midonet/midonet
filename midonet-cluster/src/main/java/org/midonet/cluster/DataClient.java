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
import java.util.Set;
import java.util.UUID;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.midonet.cluster.data.AdRoute;
import org.midonet.cluster.data.BGP;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Chain;
import org.midonet.cluster.data.IpAddrGroup;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.PortGroup;
import org.midonet.cluster.data.Route;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.TraceRequest;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.host.Interface;
import org.midonet.cluster.data.l4lb.HealthMonitor;
import org.midonet.cluster.data.l4lb.LoadBalancer;
import org.midonet.cluster.data.l4lb.Pool;
import org.midonet.cluster.data.l4lb.PoolMember;
import org.midonet.cluster.data.l4lb.VIP;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Ip4ToMacReplicatedMap;
import org.midonet.midolman.state.MacPortMap;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.IPv6Subnet;

public interface DataClient {

    /* BGP advertising routes related methods */
    @CheckForNull
    AdRoute adRoutesGet(UUID id)
            throws StateAccessException, SerializationException;

    List<AdRoute> adRoutesFindByBgp(UUID bgpId)
            throws StateAccessException, SerializationException;

    @CheckForNull BGP bgpGet(UUID id)
            throws StateAccessException, SerializationException;

    List<BGP> bgpFindByPort(UUID portId)
            throws StateAccessException, SerializationException;

    List<BGP> bgpFindByRouter(UUID routerId)
            throws StateAccessException, SerializationException;

    @CheckForNull Bridge bridgesGet(UUID id)
            throws StateAccessException, SerializationException;

    List<Bridge> bridgesGetAll() throws StateAccessException,
            SerializationException;

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

    /* Chains related methods */
    @CheckForNull Chain chainsGet(UUID id)
            throws StateAccessException, SerializationException;

    List<Chain> chainsGetAll() throws StateAccessException,
            SerializationException;

    @CheckForNull Subnet dhcpSubnetsGet(UUID bridgeId, IPv4Subnet subnetAddr)
            throws StateAccessException, SerializationException;

    List<Subnet> dhcpSubnetsGetByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException;

    List<org.midonet.cluster.data.dhcp.Host> dhcpHostsGetBySubnet(
            UUID bridgeId, IPv4Subnet subnet)
            throws StateAccessException, SerializationException;

    @CheckForNull Subnet6 dhcpSubnet6Get(UUID bridgeId, IPv6Subnet prefix)
            throws StateAccessException, SerializationException;

    List<Subnet6> dhcpSubnet6sGetByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException;

    List<V6Host> dhcpV6HostsGetByPrefix(
            UUID bridgeId, IPv6Subnet prefix)
            throws StateAccessException, SerializationException;

    @CheckForNull TunnelZone tunnelZonesGet(UUID uuid)
            throws StateAccessException, SerializationException;

    List<TunnelZone> tunnelZonesGetAll()
            throws StateAccessException, SerializationException;

    Set<TunnelZone.HostConfig> tunnelZonesGetMemberships(UUID uuid)
        throws StateAccessException;

    @CheckForNull TunnelZone.HostConfig tunnelZonesGetMembership(
            UUID uuid, UUID hostId)
            throws StateAccessException, SerializationException;

    /* load balancers related methods */

    @CheckForNull
    LoadBalancer loadBalancerGet(UUID id)
            throws StateAccessException, SerializationException;

    List<LoadBalancer> loadBalancersGetAll()
            throws StateAccessException, SerializationException;

    @CheckForNull
    HealthMonitor healthMonitorGet(UUID id)
            throws StateAccessException, SerializationException;

    List<HealthMonitor> healthMonitorsGetAll() throws StateAccessException,
            SerializationException;

    /* pool related methods */

    @CheckForNull
    Pool poolGet(UUID id)
            throws StateAccessException, SerializationException;

    List<Pool> poolsGetAll() throws StateAccessException,
            SerializationException;

    List<PoolMember> poolGetMembers(UUID id)
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

    List<Host> hostsGetAll()
            throws StateAccessException;

    List<Interface> interfacesGetByHost(UUID hostId)
            throws StateAccessException, SerializationException;

    @CheckForNull Interface interfacesGet(UUID hostId, String interfaceName)
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

    /**
     * Get a list of all IP Address Groups.
     *
     * @return List of IPAddrGroup. May be empty, but never null.
     */
    List<IpAddrGroup> ipAddrGroupsGetAll() throws StateAccessException,
            SerializationException;

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

    List<PortGroup> portGroupsGetAll() throws StateAccessException,
            SerializationException;

    /* Routes related methods */
    @CheckForNull Route routesGet(UUID id)
            throws StateAccessException, SerializationException;

    List<Route> routesFindByRouter(UUID routerId)
            throws StateAccessException, SerializationException;

    @CheckForNull Router routersGet(UUID id)
            throws StateAccessException, SerializationException;

    List<Router> routersGetAll() throws StateAccessException,
            SerializationException;

    /* Rules related methods */
    @CheckForNull Rule<?, ?> rulesGet(UUID id)
            throws StateAccessException, SerializationException;

    List<Rule<?, ?>> rulesFindByChain(UUID chainId)
            throws StateAccessException, SerializationException;

    /* Trace request methods */
    @CheckForNull
    TraceRequest traceRequestGet(UUID id)
            throws StateAccessException, SerializationException;

    List<TraceRequest> traceRequestGetAll()
            throws StateAccessException, SerializationException;

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

    Ip4ToMacReplicatedMap getIp4MacMap(UUID bridgeId)
        throws StateAccessException;
}
