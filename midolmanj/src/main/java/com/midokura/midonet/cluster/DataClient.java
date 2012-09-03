/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster;

import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.data.TunnelZone;
import com.midokura.midonet.cluster.data.Bridge;
import com.midokura.midonet.cluster.data.Host;
import com.midokura.midonet.cluster.data.Port;
import com.midokura.util.functors.Callback2;

public interface DataClient {

    /* Bridges related methods */
    Bridge bridgesGet(UUID id) throws StateAccessException;

    void bridgesDelete(UUID id) throws StateAccessException;

    Bridge bridgesCreate(@Nonnull Bridge bridge) throws StateAccessException;

    Bridge bridgesGetByName(String tenantId, String name)
         throws StateAccessException;

    /* Ports related methods */
    boolean portsExists(UUID id) throws StateAccessException;

    UUID portsCreate(Port<?, ?> port) throws StateAccessException;

    Port<?, ?> portsGet(UUID id) throws StateAccessException;

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

    UUID tunnelZonesAddMembership(UUID zoneId, TunnelZone.HostConfig<?, ?> hostConfig)
        throws StateAccessException;

    void tunnelZonesDeleteMembership(UUID zoneId, UUID membershipId)
        throws StateAccessException;

    UUID hostsCreate(UUID id, Host host) throws StateAccessException;

    /* hosts related methods */
    void hostsAddVrnPortMapping(UUID hostId, UUID portId, String localPortName)
        throws StateAccessException;

    void hostsAddDatapathMapping(UUID hostId, String datapathName)
        throws StateAccessException;

    void hostsRemoveVrnPortMapping(UUID hostId, UUID portId)
        throws StateAccessException;
}
