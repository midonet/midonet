/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Subscription;
import rx.functions.Action1;

import org.midonet.brain.services.vxgw.monitor.DeviceMonitor;
import org.midonet.brain.services.vxgw.monitor.TunnelZoneMonitor;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetEvent;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

/**
 * A class that receives a tunnel zone monitor and exposes an observable with
 * tunnel-zone related changes.
 *
 * The class also maintains a consistent state of all tunnel-zones, and computes
 * per-tunnel-zone parameters, such as the current flooding proxy.
 */
public class TunnelZoneStatePublisher {

    private static final Logger log =
        LoggerFactory.getLogger(TunnelZoneStatePublisher.class);

    private final DataClient midoClient;
    private final ZookeeperConnectionWatcher zkConnWatcher;
    private final HostStatePublisher hostMonitor;

    private final Map<UUID, TunnelZoneState> tunnelZones = new HashMap<>();

    private final Subscription monitorSubscription;

    private final Random random;

    /**
     * Creates a new tunnel zone monitor for the VXLAN gateway service.
     * @param midoClient The MidoNet data client.
     * @param zkConnWatcher The ZooKeeper connection watcher.
     * @param hostMonitor The VXLAN host monitor service.
     */
    public TunnelZoneStatePublisher(
        @Nonnull DataClient midoClient,
        @Nonnull ZookeeperConnectionWatcher zkConnWatcher,
        @Nonnull HostStatePublisher hostMonitor,
        @Nonnull Random random)
        throws DeviceMonitor.DeviceMonitorException {

        this.midoClient = midoClient;
        this.zkConnWatcher = zkConnWatcher;
        this.hostMonitor = hostMonitor;
        this.random = random;

        TunnelZoneMonitor tunnelZoneMonitor =
            new TunnelZoneMonitor(midoClient, zkConnWatcher);

        // Tunnel zone subscription
        monitorSubscription = tunnelZoneMonitor.getEntityIdSetObservable()
            .subscribe(
                new Action1<EntityIdSetEvent<UUID>>() {
                    @Override
                    public void call(
                        EntityIdSetEvent<UUID> event) {
                        switch (event.type) {
                            case CREATE:
                            case STATE:
                                onTunnelZoneCreated(event.value);
                                break;
                            case DELETE:
                                onTunnelZoneDeleted(event.value);
                                break;
                        }
                    }
                });

        tunnelZoneMonitor.notifyState();
    }

    /**
     * Returns the tunnel zone state for the specified tunnel zone identifier.
     * @param tzoneId The tunnel zone identifier.
     * @return The tunnel zone state.
     */
    public TunnelZoneState get(UUID tzoneId) {
        return tunnelZones.get(tzoneId);
    }

    /**
     * Gets or tries to create a tunnel zone state for the specified tunnel
     * zone identifier. The method returns null, if there is no ZooKeeper data
     * for the specified tunnel zone.
     * @param tzoneId The tunnel zone identifier.
     * @return The tunnel zone state.
     */
    public TunnelZoneState getOrTryCreate(UUID tzoneId) {
        try {
            return onTunnelZoneCreatedUnsafe(tzoneId);
        } catch (StateAccessException e) {
            return null;
        }
    }

    /**
     * Disposes the tunnel zone monitor.
     */
    public void dispose() {
        // Un-subscribe from the tunnel zone monitor.
        if (!monitorSubscription.isUnsubscribed()) {
            monitorSubscription.unsubscribe();
        }
    }

    /**
     * Handles the creation of a tunnel zone.
     * @param id The tunnel zone identifier.
     */
    private void onTunnelZoneCreated(final UUID id) {
        try {
            onTunnelZoneCreatedUnsafe(id);
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve state for tunnel zone {} (retrying)",
                     id, e);
            zkConnWatcher.handleError(
                "Failed to create state for tunnel zone " + id,
                new Runnable() {
                    @Override
                    public void run() {
                        onTunnelZoneCreated(id);
                    }
                }, e);
        }
    }

    /**
     * Unsafe method to handle the creation of a tunnel zone.
     * @param id The tunnel zone identifier.
     * @return The tunnel zone state.
     */
    private TunnelZoneState onTunnelZoneCreatedUnsafe(UUID id)
        throws StateAccessException {

        // If there exists a tunnel zone for this identifier do nothing.
        TunnelZoneState tunnelZone = tunnelZones.get(id);
        if (null != tunnelZone) {
            return tunnelZone;
        }

        // Create the state for the tunnel zone.
        log.debug("Tunnel zone created: {}", id);

        tunnelZone = new TunnelZoneState(id, midoClient, zkConnWatcher,
                                         hostMonitor, random);
        tunnelZones.put(id, tunnelZone);
        return tunnelZone;
    }

    /**
     * Handles the deletion of a tunnel zone.
     * @param id The tunnel zone identifier.
     */
    private void onTunnelZoneDeleted(UUID id) {
        TunnelZoneState tunnelZone = tunnelZones.remove(id);

        if (null == tunnelZone)
            return;

        log.debug("Tunnel zone deleted: {}", id);

        // Dispose the tunnel zone.
        tunnelZone.dispose();
    }
}
