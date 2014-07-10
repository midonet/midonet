/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster;

import java.util.List;
import java.util.UUID;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

/**
 * This interface defines the contract required to any device ZkManager that
 * wants to expose its set of devices to EntityIdSetMonitor and EntityMonitor.
 *
 * @param <T> the type of device
 */
public interface WatchableZkManager<K, T> {

    /**
     * Retrieve the list of identifiers to watch for modifications.
     */
    List<K> getAndWatchIdList(Runnable watcher)
        throws StateAccessException;

    /**
     * Retrieve a device and set a watcher on it, if it exists.
     *
     * @return the item data, or null if it does not exist.
     */
    T get(K key, Runnable watcher) throws StateAccessException,
                                            SerializationException;
}

