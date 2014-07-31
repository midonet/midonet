/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.sdn.state;

/**
 * A table with entry lifecycle management capabilities.
 */
public interface FlowStateLifecycle<K, V> extends FlowStateTable<K, V> {
    /**
     * Releases a reference to a key.
     */
    void unref(K key);

    /**
     * Gets the refcount for a key
     */
    int getRefCount(K key);

    /**
     * Sets the refcount for a key to the given value
     */
    void setRefCount(K key, int n);

    /**
     * Expires entries that became non-referenced longer than idleTimeMillis
     * milliseconds ago and folds over each of the expired entries.
     */
    <U> U expireIdleEntries(int idleAgeMillis, U seed,
                            FlowStateTable.Reducer<K, V, U> func);

    /**
     * Removes a key, returns the previous value associated with it.
     */
    V remove(K key);

    /**
     * Expires entries that became non-reference longer than idleTimeMillis
     * milliseconds ago.
     */
    void expireIdleEntries(int idleAgeMillis);
}
