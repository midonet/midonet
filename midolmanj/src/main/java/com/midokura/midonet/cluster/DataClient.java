/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster;

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

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface DataClient {


    /* BGP advertising routes related methods */
    AdRoute adRoutesGet(UUID id) throws StateAccessException;

    void adRoutesDelete(UUID id) throws StateAccessException;

    UUID adRoutesCreate(@Nonnull AdRoute adRoute) throws StateAccessException;

    List<AdRoute> adRoutesFindByBgp(UUID bgpId) throws StateAccessException;


    /* BGP related methods */
    BGP bgpGet(UUID id) throws StateAccessException;

    void bgpDelete(UUID id) throws StateAccessException;

    UUID bgpCreate(@Nonnull BGP bgp) throws StateAccessException;

    List<BGP> bgpFindByPort(UUID portId) throws StateAccessException;


    /* Bridges related methods */
    Bridge bridgesGet(UUID id) throws StateAccessException;

    void bridgesDelete(UUID id) throws StateAccessException;

    UUID bridgesCreate(@Nonnull Bridge bridge) throws StateAccessException;

    Bridge bridgesGetByName(String tenantId, String name)
         throws StateAccessException;

    void bridgesUpdate(@Nonnull Bridge bridge) throws StateAccessException;

    List<Bridge> bridgesFindByTenant(String tenantId)
            throws StateAccessException;


    /* Chains related methods */
    Chain chainsGet(UUID id) throws StateAccessException;

    void chainsDelete(UUID id) throws StateAccessException;

    UUID chainsCreate(@Nonnull Chain chain) throws StateAccessException;

    Chain chainsGetByName(String tenantId, String name)
            throws StateAccessException;

    List<Chain> chainsFindByTenant(String tenantId)
            throws StateAccessException;


    /* DHCP related methods */
    void dhcpSubnetsCreate(UUID bridgeId, Subnet subnet)
            throws StateAccessException;

    void dhcpSubnetsUpdate(UUID bridgeId, Subnet subnet)
        throws StateAccessException;

    void dhcpSubnetsDelete(UUID bridgeId, IntIPv4 subnetAddr)
        throws StateAccessException;

    Subnet dhcpSubnetsGet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException;

    List<Subnet> dhcpSubnetsGetByBridge(UUID bridgeId)
            throws StateAccessException;

    void dhcpHostsCreate(UUID bridgeId, IntIPv4 subnet,
                         com.midokura.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException;

    void dhcpHostsUpdate(UUID bridgeId, IntIPv4 subnet,
                         com.midokura.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException;

    com.midokura.midonet.cluster.data.dhcp.Host dhcpHostsGet(
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
    void subscribeToLocalActivePorts(Callback2<UUID, Boolean> cb);

    UUID tunnelZonesCreate(TunnelZone<?, ?> zone)
        throws StateAccessException;

    void tunnelZonesDelete(UUID uuid)
        throws StateAccessException;

    TunnelZone<?, ?> tunnelZonesGet(UUID uuid)
        throws StateAccessException;

    Set<TunnelZone.HostConfig<?, ?>> tunnelZonesGetMembership(UUID uuid)
        throws StateAccessException;

    UUID tunnelZonesAddMembership(UUID zoneId,
                                  TunnelZone.HostConfig<?, ?> hostConfig)
        throws StateAccessException;

    void tunnelZonesDeleteMembership(UUID zoneId, UUID membershipId)
        throws StateAccessException;

    UUID hostsCreate(UUID id, Host host) throws StateAccessException;

    /* hosts related methods */
    Host hostsGet(UUID hostId) throws StateAccessException;

    void hostsDelete(UUID hostId) throws StateAccessException;

    boolean hostsExists(UUID hostId) throws StateAccessException;

    boolean hostsIsAlive(UUID hostId) throws StateAccessException;

    List<Host> hostsGetAll() throws StateAccessException;

    List<Interface> interfacesGetByHost(UUID hostId)
            throws StateAccessException;

    Interface interfacesGet(UUID hostId, String interfaceName)
            throws StateAccessException;

    Integer commandsCreateForInterfaceupdate(UUID hostId, String curInterfaceId,
                                             Interface newInterface)
        throws StateAccessException;

    List<Command> commandsGetByHost(UUID hostId)
        throws StateAccessException;

    Command commandsGet(UUID hostId, Integer id) throws StateAccessException;

    void commandsDelete(UUID hostId, Integer id) throws StateAccessException;

     List<VirtualPortMapping> hostsGetVirtualPortMappingsByHost(UUID hostId)
         throws StateAccessException;

    void hostsAddVrnPortMapping(UUID hostId, UUID portId, String localPortName)
            throws StateAccessException;

    void hostsAddDatapathMapping(UUID hostId, String datapathName)
            throws StateAccessException;

    void hostsRemoveVrnPortMapping(UUID hostId, UUID portId)
            throws StateAccessException;


    /* Metrics related methods */
    Map<String, Long> metricsGetTSPoints(String type, String targetIdentifier,
                                         String metricName, long timeStart,
                                         long timeEnd);

    void metricsAddTypeToTarget(String targetIdentifier, String type);

    List<String> metricsGetTypeForTarget(String targetIdentifier);

    void metricsAddToType(String type, String metricName);

    List<String> metricsGetForType(String type);


    /* Ports related methods */
    boolean portsExists(UUID id) throws StateAccessException;

    UUID portsCreate(Port<?, ?> port) throws StateAccessException;

    void portsDelete(UUID id) throws StateAccessException;

    List<Port<?, ?>> portsFindByBridge(UUID bridgeId) throws
            StateAccessException;

    List<Port<?, ?>> portsFindPeersByBridge(UUID bridgeId)
            throws StateAccessException;

    List<Port<?, ?>> portsFindByRouter(UUID routerId) throws
            StateAccessException;

    List<Port<?, ?>> portsFindPeersByRouter(UUID routerId)
            throws StateAccessException;

    Port<?, ?> portsGet(UUID id) throws StateAccessException;

    void portsUpdate(@Nonnull Port port) throws StateAccessException;

    void portsBulkUpdate(@Nonnull List<Port> ports) throws StateAccessException;


    /* Port group related methods */
    PortGroup portGroupsGet(UUID id) throws StateAccessException;

    void portGroupsDelete(UUID id) throws StateAccessException;

    UUID portGroupsCreate(@Nonnull PortGroup portGroup)
            throws StateAccessException;

    PortGroup portGroupsGetByName(String tenantId, String name)
            throws StateAccessException;

    List<PortGroup> portGroupsFindByTenant(String tenantId)
            throws StateAccessException;


    /* Routes related methods */
    Route routesGet(UUID id) throws StateAccessException;

    void routesDelete(UUID id) throws StateAccessException;

    UUID routesCreate(@Nonnull Route route) throws StateAccessException;

    List<Route> routesFindByRouter(UUID routerId) throws StateAccessException;


    /* Routers related methods */
    Router routersGet(UUID id) throws StateAccessException;

    void routersDelete(UUID id) throws StateAccessException;

    UUID routersCreate(@Nonnull Router router) throws StateAccessException;

    void routersUpdate(@Nonnull Router router) throws StateAccessException;

    Router routersGetByName(String tenantId, String name)
            throws StateAccessException;

    List<Router> routersFindByTenant(String tenantId)
            throws StateAccessException;


    /* Rules related methods */
    Rule<?, ?> rulesGet(UUID id) throws StateAccessException;

    void rulesDelete(UUID id) throws StateAccessException;

    UUID rulesCreate(@Nonnull Rule<?, ?> rule) 
            throws StateAccessException, RuleIndexOutOfBoundsException;

    List<Rule<?, ?>> rulesFindByChain(UUID chainId) throws StateAccessException;


    /* VPN related methods */
    VPN vpnGet(UUID id) throws StateAccessException;

    void vpnDelete(UUID id) throws StateAccessException;

    UUID vpnCreate(@Nonnull VPN vpn) throws StateAccessException;

    List<VPN> vpnFindByPort(UUID portId) throws StateAccessException;
}
