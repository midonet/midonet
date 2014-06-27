/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import rx.Observable;
import rx.subjects.PublishSubject;

import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

/**
 * Maintains local state information for an existing host.
 */
public class HostState {

    public final UUID id;

    private final DataClient midoClient;
    private final ZookeeperConnectionWatcher zkConnWatcher;

    private final PublishSubject<HostState> streamIsAlive =
        PublishSubject.create();
    private final PublishSubject<HostState> streamFloodingProxyWeight =
        PublishSubject.create();

    private class IsAliveWatcher implements Watcher, Runnable {
        @Override
        public void run() {
            try {
                midoClient.hostsIsAlive(id, this);
            } catch (StateAccessException e) {
                zkConnWatcher.handleError("IsAliveWatcher " + id, this, e);
            }
        }

        @Override
        public void process(WatchedEvent watchedEvent) {
            if (!disposed) {
                streamIsAlive.onNext(HostState.this);
                run();
            }
        }
    }

    private class FloodingProxyWeightWatcher implements Watcher, Runnable {
        @Override
        public void run() {
            try {
                midoClient.hostsGetFloodingProxyWeight(id, this);
            } catch (StateAccessException e) {
                zkConnWatcher.handleError(
                    "FloodingProxyWeightWatcher " + id, this, e);
            } catch (SerializationException e) {
            }
        }

        @Override
        public void process(WatchedEvent watchedEvent) {
            if (!disposed) {
                streamFloodingProxyWeight.onNext(HostState.this);
                run();
            }
        }
    }

    private boolean disposed = false;

    /**
     * Creates a new VXGW state for the specified host identifier. The state
     * establishes watchers on the alive and flooding proxy weight properties
     * of the node.
     * @param id The host identifier.
     * @param midoClient The data client.
     */
    public HostState(@Nonnull UUID id, @Nonnull DataClient midoClient,
                     @Nonnull ZookeeperConnectionWatcher zkConnWatcher)
        throws StateAccessException, SerializationException {
        this.id = id;
        this.midoClient = midoClient;
        this.zkConnWatcher = zkConnWatcher;

        // Setup the watchers.
        midoClient.hostsIsAlive(id, new IsAliveWatcher());
        midoClient.hostsGetFloodingProxyWeight(
            id, new FloodingProxyWeightWatcher());
    }

    /**
     * Disposes the current object by blocking all notifications.
     */
    public void dispose() {
        // Prevent setting further watches on ZooKeeper.
        disposed = true;
        // Complete the notification streams.
        streamIsAlive.onCompleted();
        streamFloodingProxyWeight.onCompleted();
    }

    /**
     * Returns an observable that notifies the changes of the is alive status.
     * The method adds the subscription to a subscriptions lisy,
     * such that all subscriptions are removed when the host is deleted.
     */
    public Observable<HostState> getIsAliveObservable() {
        return streamIsAlive.asObservable();
    }


    /**
     * Returns an observable that notifies the changes of the flooding proxy
     * weight. The method adds the subscription to a subscriptions list,
     * such that all subscriptions are removed when the host is deleted.
     */
    public Observable<HostState> getFloodingProxyWeightObservable() {
        return streamFloodingProxyWeight.asObservable();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (null == obj || getClass() != obj.getClass())
            return false;

        HostState host = (HostState) obj;

        return Objects.equals(id, host.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return this.id.toString();
    }
}
