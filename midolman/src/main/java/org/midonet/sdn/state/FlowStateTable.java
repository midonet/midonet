/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.sdn.state;

import org.midonet.util.collection.Reducer;

/**
 * A table of per-flow stateful data.
 *
 * The state table interface provides the means to maintain reference counting
 * on table entries too. This allows for the implementation of table entry
 * expiration.
 *
 * @param <K> Type of the keys in the table
 * @param <V> Type of the values in the table.
 */
public interface FlowStateTable<K, V> {
    /**
     * Adds or updates an entry in this connection state table without
     * affecting its refcount, but affecting its expiration time.
     */
    void touch(K key, V value);

    /**
     * Adds an entry to this connection state table. Returns the previous value
     * for this key, null if it was unset. The reference count for the entry
     * starts at one.
     */
    V putAndRef(K key, V value);

    /**
     * Gets the current value for a give key.
     */
    V get(K key);

    /**
     * Increases the reference count for a key. Returns the value currently
     * associated with the key, or null if there's no such value and the
     * operation failed.
     */
    V ref(K key);

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
    <U> U expireIdleEntries(U seed, Reducer<K, V, U> func);

    /**
     * Folds the entries of this transaction using the specified Reducer.
     */
    <U> U fold(U seed, Reducer<K, V, U> func);
}
