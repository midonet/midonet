/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.sdn.state;

/**
 * A table with entry lifecycle management capabilities.
 */
public interface FlowStateLifecycle<K extends IdleExpiration, V> extends FlowStateTable<K, V> {
    /**
     * Releases a reference to a key.
     */
    void unref(K key);

    /**
     * Gets the refcount for a key
     */
    int getRefCount(K key);

    /**
     * Expires entries that became non-referenced longer than their
     * allowed idle expiration.
     */
    void expireIdleEntries();

    /**
     * Expires entries that became non-referenced longer than their
     * allowed idle expiration and folds over each of the expired entries.
     */
    <U> U expireIdleEntries(U seed, FlowStateTable.Reducer<K, V, U> func);

    /**
     * Removes a key, returns the previous value associated with it.
     */
    V remove(K key);
}
