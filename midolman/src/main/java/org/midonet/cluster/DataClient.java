/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2013 Midokura Pte Ltd
 */
package org.midonet.cluster;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.RuleIndexOutOfBoundsException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.data.*;
import org.midonet.cluster.data.Entity.TaggableEntity;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.cluster.data.host.Command;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.host.Interface;
import org.midonet.cluster.data.host.VirtualPortMapping;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.MAC;
import org.midonet.packets.IPv6Subnet;
import org.midonet.util.functors.Callback2;

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
    @CheckForNull BGP bgpGet(UUID id)
            throws StateAccessException, SerializationException;

    void bgpDelete(UUID id) throws StateAccessException, SerializationException;

    UUID bgpCreate(@Nonnull BGP bgp)
            throws StateAccessException, SerializationException;

    List<BGP> bgpFindByPort(UUID portId)
            throws StateAccessException, SerializationException;

    /* Vlan bridges related methods */

    @CheckForNull VlanAwareBridge vlanBridgesGetByName(String tenantid,
                                                       String name)
            throws StateAccessException, SerializationException;

    UUID vlanBridgesCreate(@Nonnull VlanAwareBridge bridge)
            throws StateAccessException, SerializationException;

    @CheckForNull
    VlanAwareBridge vlanBridgesGet(UUID id)
            throws StateAccessException, SerializationException;

    List<VlanAwareBridge> vlanBridgesFindByTenant(String tenantId)
            throws StateAccessException, SerializationException;

    void vlanBridgesUpdate(@Nonnull VlanAwareBridge bridge)
            throws StateAccessException, SerializationException;

    void vlanBridgesDelete(UUID id)
            throws StateAccessException, SerializationException;

    /* Bridges related methods */
    @CheckForNull Bridge bridgesGet(UUID id)
            throws StateAccessException, SerializationException;

    void bridgesDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID bridgesCreate(@Nonnull Bridge bridge)
            throws StateAccessException, SerializationException;

    @CheckForNull Bridge bridgesGetByName(String tenantId, String name)
            throws StateAccessException, SerializationException;

    void bridgesUpdate(@Nonnull Bridge bridge)
            throws StateAccessException, SerializationException;

    List<Bridge> bridgesGetAll() throws StateAccessException,
            SerializationException;

    List<Bridge> bridgesFindByTenant(String tenantId)
            throws StateAccessException, SerializationException;

    void ensureBridgeHasVlanDirectory(@Nonnull UUID bridgeId)
            throws StateAccessException;

    void bridgeAddMacPort(@Nonnull UUID bridgeId, @Nonnull MAC mac,
                          @Nonnull UUID portId)
        throws StateAccessException;

    boolean bridgeHasMacPort(@Nonnull UUID bridgeId, @Nonnull MAC mac,
                          @Nonnull UUID portId)
        throws StateAccessException;

    Map<MAC, UUID> bridgeGetMacPorts(@Nonnull UUID bridgeId)
        throws StateAccessException;

    void bridgeDeleteMacPort(@Nonnull UUID bridgeId, @Nonnull MAC mac,
                             @Nonnull UUID portId)
        throws StateAccessException;

    void bridgeAddIp4Mac(@Nonnull UUID bridgeId, @Nonnull IPv4Addr ip4,
                         @Nonnull MAC mac)
        throws StateAccessException;

    boolean bridgeHasIP4MacPair(@Nonnull UUID bridgeId,
                                @Nonnull IPv4Addr ip, @Nonnull MAC mac)
        throws StateAccessException;

    Map<IPv4Addr, MAC> bridgeGetIP4MacPairs(@Nonnull UUID bridgeId)
        throws StateAccessException;

    void bridgeDeleteIp4Mac(@Nonnull UUID bridgeId, @Nonnull IPv4Addr ip4,
                            @Nonnull MAC mac)
        throws StateAccessException;

    /* Chains related methods */
    @CheckForNull Chain chainsGet(UUID id)
            throws StateAccessException, SerializationException;

    void chainsDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID chainsCreate(@Nonnull Chain chain)
            throws StateAccessException, SerializationException;

    @CheckForNull Chain chainsGetByName(String tenantId, String name)
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

    void dhcpSubnetsDelete(UUID bridgeId, IntIPv4 subnetAddr)
        throws StateAccessException;

    @CheckForNull Subnet dhcpSubnetsGet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException, SerializationException;

    List<Subnet> dhcpSubnetsGetByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException;

    void dhcpHostsCreate(@Nonnull UUID bridgeId, @Nonnull IntIPv4 subnet,
                         org.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException, SerializationException;

    void dhcpHostsUpdate(@Nonnull UUID bridgeId, @Nonnull IntIPv4 subnet,
                         org.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException, SerializationException;

    @CheckForNull org.midonet.cluster.data.dhcp.Host dhcpHostsGet(
            UUID bridgeId, IntIPv4 subnet, String mac)
            throws StateAccessException, SerializationException;

    void dhcpHostsDelete(UUID bridgId, IntIPv4 subnet, String mac)
            throws StateAccessException;

    List<org.midonet.cluster.data.dhcp.Host> dhcpHostsGetBySubnet(
            UUID bridgeId, IntIPv4 subnet)
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
            throws StateAccessException, SerializationException;

    void tunnelZonesDelete(UUID uuid)
        throws StateAccessException;

    boolean tunnelZonesExists(UUID uuid) throws StateAccessException;

    @CheckForNull TunnelZone<?, ?> tunnelZonesGet(UUID uuid)
            throws StateAccessException, SerializationException;

    List<TunnelZone<?, ?>> tunnelZonesGetAll()
            throws StateAccessException, SerializationException;

    void tunnelZonesUpdate(@Nonnull TunnelZone<?, ?> zone)
            throws StateAccessException, SerializationException;

    boolean tunnelZonesMembershipExists(UUID uuid, UUID hostId)
        throws StateAccessException;

    Set<TunnelZone.HostConfig<?, ?>> tunnelZonesGetMemberships(UUID uuid)
        throws StateAccessException;

    @CheckForNull TunnelZone.HostConfig<?, ?> tunnelZonesGetMembership(
            UUID uuid, UUID hostId)
            throws StateAccessException, SerializationException;

    UUID tunnelZonesAddMembership(
            @Nonnull UUID zoneId,
            @Nonnull TunnelZone.HostConfig<?, ?> hostConfig)
            throws StateAccessException, SerializationException;

    void tunnelZonesDeleteMembership(UUID zoneId, UUID membershipId)
        throws StateAccessException;

    UUID hostsCreate(@Nonnull UUID id, @Nonnull Host host)
            throws StateAccessException, SerializationException;

    /* hosts related methods */
    @CheckForNull Host hostsGet(UUID hostId)
            throws StateAccessException, SerializationException;

    void hostsDelete(UUID hostId) throws StateAccessException;

    boolean hostsExists(UUID hostId) throws StateAccessException;

    boolean hostsIsAlive(UUID hostId) throws StateAccessException;

    List<Host> hostsGetAll()
            throws StateAccessException, SerializationException;

    List<Interface> interfacesGetByHost(UUID hostId)
            throws StateAccessException, SerializationException;

    @CheckForNull Interface interfacesGet(UUID hostId, String interfaceName)
            throws StateAccessException, SerializationException;

    Integer commandsCreateForInterfaceupdate(UUID hostId, String curInterfaceId,
                                             Interface newInterface)
            throws StateAccessException, SerializationException;

    List<Command> commandsGetByHost(UUID hostId)
            throws StateAccessException, SerializationException;

    @CheckForNull Command commandsGet(UUID hostId, Integer id)
            throws StateAccessException, SerializationException;

    void commandsDelete(UUID hostId, Integer id) throws StateAccessException;

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

    void hostsAddDatapathMapping(
            @Nonnull UUID hostId, @Nonnull String datapathName)
            throws StateAccessException, SerializationException;

    void hostsDelVrnPortMapping(UUID hostId, UUID portId)
            throws StateAccessException, SerializationException;

    /* Metrics related methods */
    Map<String, Long> metricsGetTSPoints(String type, String targetIdentifier,
                                         String metricName, long timeStart,
                                         long timeEnd);

    void metricsAddTypeToTarget(
            @Nonnull String targetIdentifier, @Nonnull String type);

    List<String> metricsGetTypeForTarget(String targetIdentifier);

    void metricsAddToType(@Nonnull String type, @Nonnull String metricName);

    List<String> metricsGetForType(String type);


    /* Ports related methods */
    boolean portsExists(UUID id) throws StateAccessException;

    UUID portsCreate(@Nonnull Port<?, ?> port)
            throws StateAccessException, SerializationException;

    void portsDelete(UUID id)
            throws StateAccessException, SerializationException;

    List<Port<?, ?>> trunkPortsFindByVlanBridge(UUID bridgeId)
            throws StateAccessException, SerializationException;

    List<Port<?, ?>> interiorPortsFindByVlanBridge(UUID bridgeId)
            throws StateAccessException, SerializationException;

    List<Port<?, ?>> portsFindByBridge(UUID bridgeId) throws
            StateAccessException, SerializationException;

    List<Port<?, ?>> portsFindPeersByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException;

    List<Port<?, ?>> portsFindByRouter(UUID routerId) throws
            StateAccessException, SerializationException;

    List<Port<?, ?>> portsFindPeersByRouter(UUID routerId)
            throws StateAccessException, SerializationException;

    @CheckForNull Port<?, ?> portsGet(UUID id)
            throws StateAccessException, SerializationException;

    void portsUpdate(@Nonnull Port port)
            throws StateAccessException, SerializationException;

    void portsLink(@Nonnull UUID portId, @Nonnull UUID peerPortId)
            throws StateAccessException, SerializationException;

    void portsUnlink(@Nonnull UUID portId)
            throws StateAccessException, SerializationException;

    List<Port<?, ?>> portsFindByPortGroup(UUID portGroupId)
            throws StateAccessException, SerializationException;


    /* Port group related methods */
    @CheckForNull PortGroup portGroupsGet(UUID id)
            throws StateAccessException, SerializationException;

    void portGroupsDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID portGroupsCreate(@Nonnull PortGroup portGroup)
            throws StateAccessException, SerializationException;

    boolean portGroupsExists(UUID id) throws StateAccessException;

    @CheckForNull PortGroup portGroupsGetByName(String tenantId, String name)
            throws StateAccessException, SerializationException;

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
    @CheckForNull Router routersGet(UUID id)
            throws StateAccessException, SerializationException;

    void routersDelete(UUID id)
            throws StateAccessException, SerializationException;

    UUID routersCreate(@Nonnull Router router)
            throws StateAccessException, SerializationException;

    void routersUpdate(@Nonnull Router router)
            throws StateAccessException, SerializationException;

    @CheckForNull Router routersGetByName(String tenantId, String name)
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

    /* PortSet related methods */

    /**
     * This should be called AddMember but since our port set membership right
     * now means hosts we named it accordingly.
     *
     * @param portSetId the id of the portset
     * @param hostId the id of the host
     * @param callback the callback to be fired when the operation is completed
     */
    void portSetsAsyncAddHost(
            UUID portSetId, UUID hostId, DirectoryCallback.Add callback);

    void portSetsAddHost(UUID portSetId, UUID hostId)
        throws StateAccessException;

    void portSetsAsyncDelHost(
            UUID portSetId, UUID hostId, DirectoryCallback.Void callback);

    void portSetsDelHost(UUID portSetId, UUID hostId)
        throws StateAccessException;

    Set<UUID> portSetsGet(UUID portSet) throws StateAccessException;

    /**
     * Adds a new tag to a resource represented by "taggable" data with id.
     *
     * @param taggable A resource to be tagged.
     * @param id An id of the resource to be tagged.
     * @param tag A tag to be added.
     * @throws StateAccessException
     */
    public void tagsAdd(@Nonnull TaggableEntity taggable, UUID id, String tag)
        throws StateAccessException;

    /**
     * Gets the data for the particular tag attached to a resource represented by
     * "taggable" with "id" UUID.
     *
     * @param taggable A parent resource to which a tag is attached.
     * @param id An id of the parent resource.
     * @param tag A tag.
     * @throws StateAccessException
     */
    public String tagsGet(@Nonnull TaggableEntity taggable, UUID id, String tag)
            throws StateAccessException;

    /**
     * Returns a list of tags attached to a resource represented by "taggable"
     * with "id" UUID.
     *
     * @param taggable A parent resource to which a tag is attached.
     * @param id An id of the parent resource.
     * @throws StateAccessException
     */
    public List<String> tagsList(@Nonnull TaggableEntity taggable, UUID id)
            throws StateAccessException;

    /**
     * Deletes a tag attached to a resource represented by "taggable" data with id.
     *
     * @param taggable A parent resource to which tag is attached.
     * @param id An id of the parent resource.
     * @param tag A tag.
     * @throws StateAccessException
     */
    public void tagsDelete(@Nonnull TaggableEntity taggable, UUID id, String tag)
        throws StateAccessException;

    /* Trace condition methods */
    UUID traceConditionCreate(@Nonnull TraceCondition traceCondition)
        throws StateAccessException, SerializationException;

    void traceConditionDelete(UUID uuid) throws StateAccessException;

    boolean traceConditionExists(UUID uuid) throws StateAccessException;

    @CheckForNull TraceCondition traceConditionGet(UUID uuid)
        throws StateAccessException, SerializationException;

    List<TraceCondition> traceConditionsGetAll()
        throws StateAccessException, SerializationException;

    /*
     * Packet Trace related methods
     */

    // First String is key (UUID traceID represented as String)
    // Second String is value (int number of trace messages represent as String)
    Map<String, String> traceIdList(int maxEntries);

    void traceIdDelete(UUID traceId);

    List<String> packetTraceGet(UUID traceId);

    void packetTraceDelete(UUID traceId);

    /**
     * Get tenants
     *
     * @return Set of tenant IDs
     * @throws StateAccessException
     */
    Set<String> tenantsGetAll() throws StateAccessException;
}
