/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.brain.services.vxgw.monitor.DeviceMonitor;
import org.midonet.brain.services.vxgw.monitor.VtepMonitor;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;

/**
 * A class that received a VTEP monitor, and exposes observables with VTEP
 * related changes for VTEPs owned by the current service.
 *
 * The class maintains a consistent state of the current VTEPS, owned and
 * unowned.
 */
public final class VtepStatePublisher {

    private static final Logger log =
        LoggerFactory.getLogger(VtepStatePublisher.class);

    private final DataClient midoClient;
    private final ZookeeperConnectionWatcher zkConnWatcher;

    private final UUID serviceId;
    private final VtepMonitor vtepMonitor;
    private final Map<IPv4Addr, VtepState> vteps = new HashMap<>();

    private final Subscription monitorSubscription;
    private final Map<IPv4Addr, Subscription> ownerSubsriptions =
        new HashMap<>();

    private final Subject<VtepState, VtepState> streamAcquire =
        PublishSubject.create();
    private final Subject<VtepState, VtepState> streamRelease =
        PublishSubject.create();

    private final Action1<VtepState> ownerChanged = new Action1<VtepState>() {
        @Override
        public void call(VtepState vtep) {
            onVtepOwnerChanged(vtep);
        }
    };

    /**
     * Creates
     * @param midoClient
     * @param zkConnWatcher
     * @throws DeviceMonitor.DeviceMonitorException
     */
    public VtepStatePublisher(@Nonnull DataClient midoClient,
                              @Nonnull ZookeeperConnectionWatcher zkConnWatcher,
                              @Nonnull UUID serviceId)
        throws DeviceMonitor.DeviceMonitorException {

        this.midoClient = midoClient;
        this.zkConnWatcher = zkConnWatcher;

        this.serviceId = serviceId;

        // VTEP subscription
        vtepMonitor = new VtepMonitor(midoClient, zkConnWatcher);

        monitorSubscription = vtepMonitor.getEntityIdSetObservable().subscribe(
            new Action1<EntityIdSetEvent<IPv4Addr>>() {
                @Override
                public void call(EntityIdSetEvent<IPv4Addr> event) {
                    switch (event.type) {
                        case CREATE:
                        case STATE:
                            onVtepCreated(event.value);
                            break;
                        case DELETE:
                            onVtepDeleted(event.value);
                    }
                }
            });
    }

    /**
     * Reissues notifications for the current state.
     */
    public synchronized void notifyState() {
        // Cleanup the current state for safety, and to ensure that
        // notifications are reissued when the VTEPs are recreated. Because this
        // method should only be called one, the cleanup should not have any
        // effect.
        while (!vteps.isEmpty()) {
            onVtepDeleted(vteps.values().iterator().next().vtepIp);
        }

        vtepMonitor.notifyState();
    }

    /**
     * Gets an observable that issues notifications when the VTEP monitor has
     * acquired the ownership of a VTEP. The VTEP can be either newly created,
     * or a VTEP that lost the previous owner.
     * @return The observable.
     */
    public Observable<VtepState> getAcquireObservable() {
        return streamAcquire.asObservable();
    }

    /**
     * Gets an observable that issues notifications when the VTEP monitor has
     * released the ownership of a VTEP. The VTEP can be a VTEP that has been
     * deleted, or a VTEP whose owner has been changed by a third party.
     * @return The observable.
     */
    public Observable<VtepState> getReleaseObservable() {
        return streamRelease.asObservable();
    }

    /**
     * Disposes the VTEP monitor by completing all observables.
     */
    public synchronized void dispose() {
        // Un-subscribe from the VTEP monitor.
        if (!monitorSubscription.isUnsubscribed()) {
            monitorSubscription.unsubscribe();
        }

        // Release the ownership of the owned VTEPs.
        for (VtepState vtep : vteps.values()) {
            // Dispose the VTEP object which notifies the ownership change.
            vtep.dispose();
            // Remove the owner change subscription.
            ownerSubsriptions.remove(vtep.vtepIp).unsubscribe();
        }

        // Complete the streams.
        streamAcquire.onCompleted();
        streamRelease.onCompleted();

        assert(ownerSubsriptions.isEmpty());
    }

    /**
     * Creates a new state for a VTEP.
     * @param ip The VTEP management IP address.
     */
    private void onVtepCreated(final IPv4Addr ip) {
        log.debug("VTEP created: {}", ip);

        try {
            onVtepCreatedUnsafe(ip);
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve state for VTEP {} (retrying)", ip, e);
            zkConnWatcher.handleError(
                "Failed to update state for VTEP " + ip,
                new Runnable() {
                    @Override
                    public void run() {
                        onVtepCreated(ip);
                    }
                }, e);
        } catch (SerializationException e) {
            log.error("Cannot deserialize state for VTEP {} (aborting)", ip, e);
        }
    }

    /**
     * Unsafe method that handles the creation of a new VTEP.
     * @param ip The VTEP management IP address.
     */
    private synchronized void onVtepCreatedUnsafe(IPv4Addr ip)
        throws StateAccessException, SerializationException {
        // If the VTEP state already exists, do nothing.
        if (vteps.containsKey(ip)) {
            log.debug("A state for VTEP {} already exists (ignoring)", ip);
            return;
        }

        // Create a new VTEP state, which establishes a watcher for the VTEP
        // ownership.
        VtepState vtep = new VtepState(ip, serviceId, midoClient,
                                            zkConnWatcher);
        vteps.put(ip, vtep);

        // Subscribe to owner changes.
        ownerSubsriptions.put(
            ip, vtep.getOwnerObservable().subscribe(ownerChanged));

        // If the VTEP is owned by the current service, notify the VTEP
        // creation.
        if (vtep.isOwned()) {
            streamAcquire.onNext(vtep);
        }
    }

    /**
     * Deletes an existing state for a VTEP.
     * @param ip The VTEP management IP address.
     */
    private synchronized void onVtepDeleted(IPv4Addr ip) {
        VtepState vtep = vteps.remove(ip);

        if (null == vtep)
            return;

        log.debug("VTEP deleted: {}", ip);

        // Dispose the VTEP state, which removes the VTEP ownership and
        // completes all notifications.
        vtep.dispose();

        // Unsubscribe from the VTEP.
        ownerSubsriptions.remove(ip).unsubscribe();
    }

    /**
     * Handles the change in ownership for a VTEP.
     * @param vtep The VTEP state.
     */
    private void onVtepOwnerChanged(VtepState vtep) {
        log.debug("VTEP {} ownership changed by {}", vtep.vtepIp, vtep.ownerId);

        if (vtep.isOwned()) {
            streamAcquire.onNext(vtep);
        } else {
            streamRelease.onNext(vtep);
        }
    }
}
