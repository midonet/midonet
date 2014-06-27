/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw.monitor;

import java.util.UUID;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetMonitor;
import org.midonet.cluster.EntityMonitor;
import org.midonet.cluster.data.Bridge;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;

/**
 * This class monitors the bridges of the system, exposing observable streams
 * with creations, deletions and updates.
 *
 * Internally, it coordinates a global entityIdSetMonitor to detect creations
 * and removals, and an entityMonitor to track changes to individual bridges.
 */
public class BridgeMonitor extends DeviceMonitor<UUID, Bridge> {

    public BridgeMonitor(DataClient midoClient,
                         ZookeeperConnectionWatcher zkConnWatcher)
        throws DeviceMonitorException {
        super(midoClient, zkConnWatcher);
    }

    @Override
    protected EntityMonitor<UUID, BridgeZkManager.BridgeConfig,
        Bridge> getEntityMonitor() {
        return midoClient.bridgesGetMonitor(zkConnWatcher);
    }

    @Override
    protected EntityIdSetMonitor<UUID> getEntityIdSetMonitor()
        throws StateAccessException {
        return midoClient.bridgesGetUuidSetMonitor(zkConnWatcher);
    }
}

