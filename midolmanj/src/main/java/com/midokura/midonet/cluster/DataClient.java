/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster;

import java.util.UUID;
import javax.annotation.Nonnull;

import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.data.Bridge;
import com.midokura.midonet.cluster.data.Port;

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

    /* hosts related methods */
    void hostsAddVrnPortMapping(UUID hostId, UUID portId, String localPortName)
        throws StateAccessException;

    void hostsAddDatapathMapping(UUID hostId, String datapathName)
        throws StateAccessException;

    void hostsRemoveVrnPortMapping(UUID hostId, UUID portId)
        throws StateAccessException;

    /**
     * Inform the storage cluster that the port is active. This may be used by
     * the cluster to do trigger related processing e.g. updating the router's
     * forwarding table if this port belongs to a router.
     *
     * @param portID
     * @param active
     */
    void portsSetActive(UUID portID, boolean active);
}
