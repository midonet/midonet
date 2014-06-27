/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import org.midonet.brain.services.vxgw.monitor.HostMonitor;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetEvent;
import org.midonet.cluster.data.host.Host;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

/**
 * A class that receives a host monitor and exposes an observable with all
 * host related changes, including host creation, deletion, update and changes
 * to the alive and flooding proxy weight properties.
 *
 * The class maintains a consistent state of the current hosts.
 */
public class HostStatePublisher {

    private static final Logger log =
        LoggerFactory.getLogger(HostStatePublisher.class);

    private final DataClient midoClient;
    private final ZookeeperConnectionWatcher zkConnWatcher;

    private final Map<UUID, HostState> hosts = new HashMap<>();

    private List<Subscription> subscriptions = new ArrayList<>();

    private Subject<HostState, HostState> streamCreate =
        PublishSubject.create();
    private Subject<HostState, HostState> streamDelete =
        PublishSubject.create();

    /**
     * Creates a new host monitor for the VXLAN gateway service.
     * @param midoClient The MidoNet data client.
     * @param zkConnWatcher The ZooKeeper connection watcher.
     */
    public HostStatePublisher(@Nonnull DataClient midoClient,
                              @Nonnull ZookeeperConnectionWatcher zkConnWatcher)
        throws DeviceMonitor.DeviceMonitorException {

        this.midoClient = midoClient;
        this.zkConnWatcher = zkConnWatcher;

        HostMonitor hostMonitor = new HostMonitor(midoClient, zkConnWatcher);

        // Host subscription
        subscriptions.add(hostMonitor.getEntityIdSetObservable().subscribe(
            new Action1<EntityIdSetEvent<UUID>>() {
                @Override
                public void call(EntityIdSetEvent<UUID> event) {
                    switch (event.type) {
                        case CREATE:
                        case STATE:
                            onHostCreated(event.value);
                            break;
                        case DELETE:
                            onHostDeleted(event.value);
                            break;
                    }
                }
            }));
        subscriptions.add(hostMonitor.getEntityObservable().subscribe(
            new Action1<Host>() {
                @Override
                public void call(Host host) {
                    onHostUpdated(host);
                }
            }));

        hostMonitor.notifyState();
    }

    /**
     * Returns the host state for the specified host identifier.
     * @param hostId The host identifier.
     * @return The host state.
     */
    public HostState get(UUID hostId) {
        return hosts.get(hostId);
    }

    /**
     * Gets or tries to create a host state for the specified host identifier.
     * The method returns null, if there is no ZooKeeper data for the
     * specified host.
     * @param hostId The host identifier.
     * @return The host state.
     */
    public HostState getOrTryCreate(UUID hostId) {
        try {
            return onHostCreatedUnsafe(hostId);
        } catch (StateAccessException | SerializationException e) {
            return null;
        }
    }

    /**
     * Gets an observable that issues notifications when a host state is
     * created.
     * @return The observable.
     */
    public Observable<HostState> getCreateObservable() {
        return streamCreate.asObservable();
    }

    /**
     * Gets an observable that issues notifications when a host state is
     * deleted.
     * @return The observable.
     */
    public Observable<HostState> getDeleteObservable() {
        return streamDelete.asObservable();
    }

    /**
     * Disposes the host monitor.
     */
    public void dispose() {
        // Un-subscribe from the host monitor.
        for (Subscription subscription : subscriptions) {
            if (!subscription.isUnsubscribed()) {
                subscription.unsubscribe();
            }
        }

        // Complete the observables.
        streamCreate.onCompleted();
        streamDelete.onCompleted();
    }

    /**
     * Handles the creation of a new host.
     * @param hostId The host identifier.
     */
    private void onHostCreated(final UUID hostId) {
        log.debug("Host created: {}", hostId);

        // Create the state for the host.
        try {
            onHostCreatedUnsafe(hostId);
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve state for host {}. Retrying.", hostId, e);
            zkConnWatcher.handleError(
                "Failed to create state for host " + hostId,
                new Runnable() {
                    @Override
                    public void run() {
                        onHostCreated(hostId);
                    }
                }, e);
        } catch (SerializationException e) {
            log.error("Cannot deserialize state for host {}. Aborting.",
                      hostId, e);
        }
    }

    /**
     * Unsafe method to handle the creation of a new host.
     * @param hostId The host identifier.
     */
    private HostState onHostCreatedUnsafe(UUID hostId)
        throws StateAccessException, SerializationException {

        // Check whether the state already exists.
        HostState hostState = hosts.get(hostId);
        if (null != hostState) {
            return  hostState;
        }

        // Create a new state, which establishes a watcher for the flooding
        // proxy weight. If creating the host state fails, the constructor
        // throws an exception.
        hostState = new HostState(hostId, midoClient, zkConnWatcher);
        hosts.put(hostId, hostState);

        // Notify the host creation.
        streamCreate.onNext(hostState);

        return hostState;
    }

    /**
     * Handles the update of an existing host.
     * @param host The host.
     */
    private void onHostUpdated(final Host host) {
        log.debug("Host updated: {}", host.getId());

        try {
            onHostCreatedUnsafe(host.getId());
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve state for host {}. Retrying.",
                     host.getId(), e);
            zkConnWatcher.handleError(
                "Failed to update state for host " + host.getId(),
                new Runnable() {
                    @Override
                    public void run() {
                        onHostUpdated(host);
                    }
                }, e);
        } catch (SerializationException e) {
            log.error("Cannot deserialize state for host {}. Aborting",
                      host.getId(), e);
        }
    }

    /**
     * Handles the deletion of an existing host.
     * @param id The host identifier.
     */
    private void onHostDeleted(UUID id) {
        HostState hostState = hosts.remove(id);

        if (null == hostState)
            return;

        log.debug("Host deleted: {}", id);

        // Notify the host deletion.
        streamDelete.onNext(hostState);

        // Dispose the host state.
        hostState.dispose();
    }
}
