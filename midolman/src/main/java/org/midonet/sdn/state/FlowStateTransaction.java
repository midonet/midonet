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

import java.util.ArrayList;
import java.util.HashSet;

import org.midonet.util.collection.Reducer;

/**
 * Represents an open transaction on a per-flow stateful data table.
 *
 * The transaction implements the FlowStateTable interface, providing a view of
 * the underlying table with the transaction data superimposed. Table reads from
 * transaction objects will fall back to the underlying table if the transaction
 * does not contain a modification for the given key. Note that unsetting or
 * deleting a key is not a supported operation. Keys are meant to expire
 * naturally through reference counting and idle expiration.
 *
 * Note that a transaction may hold ref() operations that do not refer to
 * keys in the transaction, but to keys that pre-exist in the underlying table.
 * unref() works similarly, for completeness, because it's not expected to
 * be used on transactions.
 *
 * IMPLEMENTATION NOTES:
 *
 * A transaction object should be reusable from one flow processed to the next.
 * We keep this in mind when choosing data structures for internal storage
 * inside this class. Chosen structures are reusable without incurring in
 * extra allocations or produced garbage. Namely we prefer to use arrays
 * to full fledged collections. Transactions are expected to hold very few
 * operations, iterating through internal arrays will also be cheaper than
 * hash table look-ups. When this object gets reused for the next flow, arrays
 * are simply zeroed.
 */
public class FlowStateTransaction<K, V> {
    private FlowStateTable<K, V> parent;

    private ArrayList<K> keys = new ArrayList<>();
    private ArrayList<V> values = new ArrayList<>();
    private ArrayList<K> refs = new ArrayList<>();
    private ArrayList<K> touchKeys = new ArrayList<>();
    private ArrayList<V> touchVals = new ArrayList<>();
    private HashSet<K> deletes = new HashSet<>();

    public FlowStateTransaction(FlowStateTable<K, V> underlyingState) {
        parent = underlyingState;
    }

    public int size() {
        return keys.size() + refs.size();
    }

    /**
     * Discards the ongoing transaction, clearing all state in it.
     */
    public void flush() {
        keys.clear();
        values.clear();
        refs.clear();
        deletes.clear();
        touchKeys.clear();
        touchVals.clear();
    }

    /**
     * Commits this transaction to the underlying FlowStateTable table.
     */
    public void commit() {
        for (int i = 0; i < keys.size(); i++)
            parent.putAndRef(keys.get(i), values.get(i));

        for (int i = 0; i < refs.size(); i++)
            parent.ref(refs.get(i));

        for (int i = 0; i < touchKeys.size(); i++)
            parent.touch(touchKeys.get(i), touchVals.get(i));
    }

    public <U> U fold(U seed, Reducer<K, V, U> func) {
        for (int i = 0; i < keys.size(); i++)
            seed = func.apply(seed, keys.get(i), values.get(i));
        for (int i = 0; i < refs.size(); i++)
            seed = func.apply(seed, refs.get(i), parent.get(refs.get(i)));
        return seed;
    }

    public V putAndRef(K key, V value) {
        if (deletes.contains(key)) {
            deletes.remove(key);
        } else {
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).equals(key)) {
                    V oldV = values.get(i);
                    values.add(i, value);
                    return oldV;
                }
            }
        }
        keys.add(key);
        values.add(value);
        return parent.get(key);
    }

    public void touch(K key, V value) {
        if (deletes.contains(key))
            deletes.remove(key);
        touchKeys.add(key);
        touchVals.add(value);
    }

    public V get(K key) {
        if (deletes.contains(key))
            return null;

        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equals(key))
                return values.get(i);
        }
        return parent.get(key);
    }

    public V ref(K key) {
        refs.add(key);
        return get(key);
    }

    public V remove(K key) {
        for (int i  = 0; i < keys.size(); ++i) {
            if (keys.get(i).equals(key)) {
                keys.remove(i);
                values.remove(i);
                break;
            }
        }

        for (int i  = 0; i < touchKeys.size(); ++i) {
            if (touchKeys.get(i).equals(key)) {
                touchKeys.remove(i);
                touchVals.remove(i);
                break;
            }
        }

        for (int i  = 0; i < refs.size(); ++i) {
            if (refs.get(i).equals(key)) {
                refs.remove(i);
                break;
            }
        }

        deletes.add(key);
        return null;
    }
}
