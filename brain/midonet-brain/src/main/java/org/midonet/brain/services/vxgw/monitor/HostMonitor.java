/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.brain.services.vxgw.monitor;

import java.util.UUID;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetMonitor;
import org.midonet.cluster.EntityMonitor;
import org.midonet.cluster.data.host.Host;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

/**
 * Monitors all hosts of the system, exposing observable streams with creation,
 * deletion and update notifications.
 */
public final class HostMonitor extends DeviceMonitor<UUID, Host> {

    public HostMonitor(DataClient midoClient,
                       ZookeeperConnectionWatcher zkConnWatcher) {
        super(midoClient, zkConnWatcher);
    }

    @Override
    protected EntityMonitor<UUID, HostDirectory.Metadata, Host>
        getEntityMonitor() {
        return midoClient.hostsGetMonitor(zkConnWatcher);
    }

    @Override
    protected EntityIdSetMonitor<UUID> getEntityIdSetMonitor()
        throws StateAccessException {
        return midoClient.hostsGetUuidSetMonitor(zkConnWatcher);
    }
}
