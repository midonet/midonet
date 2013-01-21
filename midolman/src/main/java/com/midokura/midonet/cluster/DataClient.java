/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import com.midokura.midolman.state.DirectoryCallback;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.data.*;
import com.midokura.midonet.cluster.data.dhcp.Subnet;
import com.midokura.midonet.cluster.data.host.Command;
import com.midokura.midonet.cluster.data.host.Host;
import com.midokura.midonet.cluster.data.host.Interface;
import com.midokura.midonet.cluster.data.host.VirtualPortMapping;
import com.midokura.packets.IntIPv4;
import com.midokura.util.functors.Callback2;


public interface DataClient {

    /* BGP advertising routes related methods */
    @CheckForNull AdRoute adRoutesGet(UUID id) throws StateAccessException;

    void adRoutesDelete(UUID id) throws StateAccessException;

    UUID adRoutesCreate(@Nonnull AdRoute adRoute) throws StateAccessException;

    List<AdRoute> adRoutesFindByBgp(UUID bgpId) throws StateAccessException;


    /* BGP related methods */
    @CheckForNull BGP bgpGet(UUID id) throws StateAccessException;

    void bgpDelete(UUID id) throws StateAccessException;

    UUID bgpCreate(@Nonnull BGP bgp) throws StateAccessException;

    List<BGP> bgpFindByPort(UUID portId) throws StateAccessException;


    /* Bridges related methods */
    @CheckForNull Bridge bridgesGet(UUID id) throws StateAccessException;

    void bridgesDelete(UUID id) throws StateAccessException;

    UUID bridgesCreate(@Nonnull Bridge bridge) throws StateAccessException;

    @CheckForNull Bridge bridgesGetByName(String tenantId, String name)
         throws StateAccessException;

    void bridgesUpdate(@Nonnull Bridge bridge) throws StateAccessException;

    List<Bridge> bridgesFindByTenant(String tenantId)
            throws StateAccessException;


    /* Chains related methods */
    @CheckForNull Chain chainsGet(UUID id) throws StateAccessException;

    void chainsDelete(UUID id) throws StateAccessException;

    UUID chainsCreate(@Nonnull Chain chain) throws StateAccessException;

    @CheckForNull Chain chainsGetByName(String tenantId, String name)
            throws StateAccessException;

    List<Chain> chainsFindByTenant(String tenantId)
            throws StateAccessException;


    /* DHCP related methods */
    void dhcpSubnetsCreate(@Nonnull UUID bridgeId, @Nonnull Subnet subnet)
            throws StateAccessException;

    void dhcpSubnetsUpdate(@Nonnull UUID bridgeId, @Nonnull Subnet subnet)
        throws StateAccessException;

    void dhcpSubnetsDelete(UUID bridgeId, IntIPv4 subnetAddr)
        throws StateAccessException;

    @CheckForNull Subnet dhcpSubnetsGet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException;

    List<Subnet> dhcpSubnetsGetByBridge(UUID bridgeId)
            throws StateAccessException;

    void dhcpHostsCreate(@Nonnull UUID bridgeId, @Nonnull IntIPv4 subnet,
                         com.midokura.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException;

    void dhcpHostsUpdate(@Nonnull UUID bridgeId, @Nonnull IntIPv4 subnet,
                         com.midokura.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException;

    @CheckForNull com.midokura.midonet.cluster.data.dhcp.Host dhcpHostsGet(
            UUID bridgeId, IntIPv4 subnet, String mac)
        throws StateAccessException;

    void dhcpHostsDelete(UUID bridgId, IntIPv4 subnet, String mac)
            throws StateAccessException;

    List<com.midokura.midonet.cluster.data.dhcp.Host> dhcpHostsGetBySubnet(
            UUID bridgeId, IntIPv4 subnet)
            throws StateAccessException;


    /**
     * Inform the storage cluster that the port is active. This may be used by
     * the cluster to do trigger related processing e.g. updating the router's
     * forwarding table if this port belongs to a router.
     *
     * @param portID the id of the port
     * @param active true / false depending on what state we want in the end
     *               for the port
     */
    void portsSetLocalAndActive(UUID portID, boolean active);

    /**
     * Register a callback to be called whenever a port becomes "local and
     * active" or stops being so. This may be used e.g. by the BGP Manager
     * to discover the local ports, so that it may then watch those specific
     * ports and manage their BGPs (if any).
     * @param cb
     */
    void subscribeToLocalActivePorts(@Nonnull Callback2<UUID, Boolean> cb);

    UUID tunnelZonesCreate(@Nonnull TunnelZone<?, ?> zone)
        throws StateAccessException;

    void tunnelZonesDelete(UUID uuid)
        throws StateAccessException;

    boolean tunnelZonesExists(UUID uuid) throws StateAccessException;

    @CheckForNull TunnelZone<?, ?> tunnelZonesGet(UUID uuid)
        throws StateAccessException;

    List<TunnelZone<?, ?>> tunnelZonesGetAll() throws StateAccessException;

    void tunnelZonesUpdate(@Nonnull TunnelZone<?, ?> zone) throws StateAccessException;

    boolean tunnelZonesMembershipExists(UUID uuid, UUID hostId)
        throws StateAccessException;

    Set<TunnelZone.HostConfig<?, ?>> tunnelZonesGetMemberships(UUID uuid)
        throws StateAccessException;

    @CheckForNull TunnelZone.HostConfig<?, ?> tunnelZonesGetMembership(UUID uuid,
                                                         UUID hostId)
        throws StateAccessException;

    UUID tunnelZonesAddMembership(@Nonnull UUID zoneId,
                                  @Nonnull TunnelZone.HostConfig<?, ?> hostConfig)
        throws StateAccessException;

    void tunnelZonesDeleteMembership(UUID zoneId, UUID membershipId)
        throws StateAccessException;

    UUID hostsCreate(@Nonnull UUID id, @Nonnull Host host) throws StateAccessException;

    /* hosts related methods */
    @CheckForNull Host hostsGet(UUID hostId) throws StateAccessException;

    void hostsDelete(UUID hostId) throws StateAccessException;

    boolean hostsExists(UUID hostId) throws StateAccessException;

    boolean hostsIsAlive(UUID hostId) throws StateAccessException;

    List<Host> hostsGetAll() throws StateAccessException;

    List<Interface> interfacesGetByHost(UUID hostId)
            throws StateAccessException;

    @CheckForNull Interface interfacesGet(UUID hostId, String interfaceName)
            throws StateAccessException;

    Integer commandsCreateForInterfaceupdate(UUID hostId, String curInterfaceId,
                                             Interface newInterface)
        throws StateAccessException;

    List<Command> commandsGetByHost(UUID hostId)
        throws StateAccessException;

    @CheckForNull Command commandsGet(UUID hostId, Integer id) throws StateAccessException;

    void commandsDelete(UUID hostId, Integer id) throws StateAccessException;

    List<VirtualPortMapping> hostsGetVirtualPortMappingsByHost(UUID hostId)
         throws StateAccessException;

    boolean hostsVirtualPortMappingExists(UUID hostId, UUID portId)
        throws StateAccessException;

    @CheckForNull VirtualPortMapping hostsGetVirtualPortMapping(UUID hostId, UUID portId)
        throws StateAccessException;

    void hostsAddVrnPortMapping(@Nonnull UUID hostId, @Nonnull UUID portId,
                                @Nonnull String localPortName)
        throws StateAccessException;

    void hostsAddDatapathMapping(@Nonnull UUID hostId, @Nonnull String datapathName)
            throws StateAccessException;

    void hostsDelVrnPortMapping(UUID hostId, UUID portId)
            throws StateAccessException;

    /* Metrics related methods */
    Map<String, Long> metricsGetTSPoints(String type, String targetIdentifier,
                                         String metricName, long timeStart,
                                         long timeEnd);

    void metricsAddTypeToTarget(@Nonnull String targetIdentifier, @Nonnull String type);

    List<String> metricsGetTypeForTarget(String targetIdentifier);

    void metricsAddToType(@Nonnull String type, @Nonnull String metricName);

    List<String> metricsGetForType(String type);


    /* Ports related methods */
    boolean portsExists(UUID id) throws StateAccessException;

    UUID portsCreate(@Nonnull Port<?, ?> port) throws StateAccessException;

    void portsDelete(UUID id) throws StateAccessException;

    List<Port<?, ?>> portsFindByBridge(UUID bridgeId) throws
            StateAccessException;

    List<Port<?, ?>> portsFindPeersByBridge(UUID bridgeId)
            throws StateAccessException;

    List<Port<?, ?>> portsFindByRouter(UUID routerId) throws
            StateAccessException;

    List<Port<?, ?>> portsFindPeersByRouter(UUID routerId)
            throws StateAccessException;

    @CheckForNull Port<?, ?> portsGet(UUID id) throws StateAccessException;

    void portsUpdate(@Nonnull Port port) throws StateAccessException;

    void portsLink(@Nonnull UUID portId, @Nonnull UUID peerPortId)
        throws StateAccessException;

    void portsUnlink(@Nonnull UUID portId) throws StateAccessException;

    List<Port<?, ?>> portsFindByPortGroup(UUID portGroupId)
        throws StateAccessException;


    /* Port group related methods */
    @CheckForNull PortGroup portGroupsGet(UUID id) throws StateAccessException;

    void portGroupsDelete(UUID id) throws StateAccessException;

    UUID portGroupsCreate(@Nonnull PortGroup portGroup)
            throws StateAccessException;

    boolean portGroupsExists(UUID id) throws StateAccessException;

    @CheckForNull PortGroup portGroupsGetByName(String tenantId, String name)
            throws StateAccessException;

    List<PortGroup> portGroupsFindByPort(UUID portId)
            throws StateAccessException;

    List<PortGroup> portGroupsFindByTenant(String tenantId)
            throws StateAccessException;

    boolean portGroupsIsPortMember(UUID id, UUID portId)
        throws StateAccessException;

    void portGroupsAddPortMembership(@Nonnull UUID id, @Nonnull UUID portId)
        throws StateAccessException;

    void portGroupsRemovePortMembership(UUID id, UUID portId)
        throws StateAccessException;


    /* Routes related methods */
    @CheckForNull Route routesGet(UUID id) throws StateAccessException;

    void routesDelete(UUID id) throws StateAccessException;

    UUID routesCreate(@Nonnull Route route) throws StateAccessException;

    UUID routesCreateEphemeral(@Nonnull Route route) throws StateAccessException;

    List<Route> routesFindByRouter(UUID routerId) throws StateAccessException;


    /* Routers related methods */
    @CheckForNull Router routersGet(UUID id) throws StateAccessException;

    void routersDelete(UUID id) throws StateAccessException;

    UUID routersCreate(@Nonnull Router router) throws StateAccessException;

    void routersUpdate(@Nonnull Router router) throws StateAccessException;

    @CheckForNull Router routersGetByName(String tenantId, String name)
            throws StateAccessException;

    List<Router> routersFindByTenant(String tenantId)
            throws StateAccessException;


    /* Rules related methods */
    @CheckForNull Rule<?, ?> rulesGet(UUID id) throws StateAccessException;

    void rulesDelete(UUID id) throws StateAccessException;

    UUID rulesCreate(@Nonnull Rule<?, ?> rule)
            throws StateAccessException, RuleIndexOutOfBoundsException;

    List<Rule<?, ?>> rulesFindByChain(UUID chainId) throws StateAccessException;


    /* VPN related methods */
    @CheckForNull VPN vpnGet(UUID id) throws StateAccessException;

    void vpnDelete(UUID id) throws StateAccessException;

    UUID vpnCreate(@Nonnull VPN vpn) throws StateAccessException;

    List<VPN> vpnFindByPort(UUID portId) throws StateAccessException;

    /* PortSet related methods */

    /**
     * This should be called AddMember but since our port set membership right
     * now means hosts we named it accordingly.
     *
     * @param portSetId the id of the portset
     * @param hostId the id of the host
     * @param callback the callback to be fired when the operation is completed
     */
    void portSetsAsyncAddHost(UUID portSetId, UUID hostId, DirectoryCallback.Add callback);

    void portSetsAddHost(UUID portSetId, UUID hostId)
        throws StateAccessException;

    void portSetsAsyncDelHost(UUID portSetId, UUID hostId, DirectoryCallback.Void callback);

    void portSetsDelHost(UUID portSetId, UUID hostId)
        throws StateAccessException;

    Set<UUID> portSetsGet(UUID portSet) throws StateAccessException;
}
