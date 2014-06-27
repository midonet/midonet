/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.brain.services.vxgw.monitor;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityMonitor;
import org.midonet.cluster.EntityIdSetMonitor;
import org.midonet.cluster.data.VTEP;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.midolman.state.zkManagers.VtepZkManager;
import org.midonet.packets.IPv4Addr;

/**
 * Monitors all VTEPS of the system, exposing observable streams with creation,
 * deletion and update notifications.
 */
public class VtepMonitor extends DeviceMonitor<IPv4Addr, VTEP> {

    public VtepMonitor(DataClient midoClient,
                       ZookeeperConnectionWatcher zkConnWatcher)
        throws DeviceMonitorException {
        super(midoClient, zkConnWatcher);
    }

    @Override
    protected EntityMonitor<IPv4Addr, VtepZkManager.VtepConfig,
        VTEP> getEntityMonitor() {
        return midoClient.vtepsGetMonitor(zkConnWatcher);
    }

    @Override
    protected EntityIdSetMonitor<IPv4Addr> getEntityIdSetMonitor()
        throws StateAccessException {
        return midoClient.vtepsGetAllSetMonitor(zkConnWatcher);
    }
}
