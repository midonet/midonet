/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.brain.services.vxgw.monitor;

import java.util.UUID;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetMonitor;
import org.midonet.cluster.EntityMonitor;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.host.Host;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

/**
 * Monitors all tunnel-zones of the system, exposing observable streams with
 * creation, deletion and update notifications.
 */
public class TunnelZoneMonitor extends DeviceMonitor<UUID, TunnelZone> {

    public TunnelZoneMonitor(DataClient midoClient,
                             ZookeeperConnectionWatcher zkConnWatcher)
        throws DeviceMonitorException {
        super(midoClient, zkConnWatcher);
    }

    @Override
    protected EntityMonitor<UUID, TunnelZone.Data, TunnelZone> getEntityMonitor() {
        return midoClient.tunnelZonesGetMonitor(zkConnWatcher);
    }

    @Override
    protected EntityIdSetMonitor<UUID> getEntityIdSetMonitor()
        throws StateAccessException {
        return midoClient.tunnelZonesGetUuidSetMonitor(zkConnWatcher);
    }
}
