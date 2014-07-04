/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.functions.Action1;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetMonitor;
import org.midonet.cluster.EntityMonitor;
import org.midonet.cluster.data.Bridge;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

/**
 * This class monitors the bridges of the system, exposing observable streams
 * with creations, deletions and updates.
 *
 * Internally, it coordinates a global entityIdSetMonitor to detect creations
 * and removals, and an entityMonitor to track changes to individual bridges.
 */
public class BridgeMonitor {

    private static final Logger log =
        LoggerFactory.getLogger(BridgeMonitor.class);

    private final DataClient midoClient;
    private final ZookeeperConnectionWatcher zkConnWatcher;
    private final EntityMonitor<?, Bridge> bridgeMon;
    private final EntityIdSetMonitor bridgeSetMon;

    public class BridgeMonitorException extends RuntimeException {
        public BridgeMonitorException(Throwable cause) {
            super("Failed to create a bridge monitor", cause);
        }
    }

    private final Action1<UUID> bridgeCreation = new Action1<UUID>() {
        @Override
        public void call(UUID bridgeId) {
            bridgeMon.watch(bridgeId);
        }
    };

    public BridgeMonitor(DataClient midoClient,
                         ZookeeperConnectionWatcher zkConnWatcher)
        throws BridgeMonitorException {
        this.midoClient = midoClient;
        this.zkConnWatcher = zkConnWatcher;
        this.bridgeMon = this.midoClient.bridgesGetMonitor(zkConnWatcher);
        try {
            this.bridgeSetMon =
                this.midoClient.bridgesGetUuidSetMonitor(zkConnWatcher);
        } catch (StateAccessException e) {
            throw new BridgeMonitorException(e);
        }
        this.bridgeSetMon.created().subscribe(bridgeCreation);
    }

    /**
     * Get the list of currently known bridge identifiers.
     */
    public Set<UUID> getCurrentBridgeIds() {
        return bridgeSetMon.getSnapshot();
    }

    /**
     * Get the observable for device additions.
     */
    public Observable<UUID> created() {
        return bridgeSetMon.created();
    }

    /**
     * Get the observable for device deletions.
     */
    public Observable<UUID> deleted() {
        return bridgeSetMon.deleted();
    }

    /**
     * Get the observable for device updates.
     */
    public Observable<Bridge> updated() {
        return bridgeMon.updated();
    }

}

