package org.midonet.sdn.state;

import java.util.ArrayList;

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
public class FlowStateTransaction<K, V> implements FlowStateTable<K, V> {
    private FlowStateTable<K, V> parent;

    private ArrayList<K> keys = new ArrayList<>();
    private ArrayList<V> values = new ArrayList<>();
    private ArrayList<K> refcounts = new ArrayList<>();

    public FlowStateTransaction(FlowStateTable<K, V> underlyingState) {
        parent = underlyingState;
    }

    /**
     * Discards the ongoing transaction, clearing all state in it.
     */
    public void flush() {
        keys.clear();
        values.clear();
        refcounts.clear();
    }

    /**
     * Commits this transaction to the underlying FlowStateTable table.
     */
    public void commit() {
        for (int i = 0; i < keys.size(); i++)
            parent.putAndRef(keys.get(i), values.get(i));

        for (int i = 0; i < refcounts.size(); i++)
            parent.ref(refcounts.get(i));
    }

    @Override
    public <U> U fold(U seed, Reducer<K, V, U> func) {
        for (int i = 0; i < keys.size(); i++) {
            seed = func.apply(seed, keys.get(i), values.get(i));
        }
        return seed;
    }

    @Override
    public V putAndRef(K key, V value) {
        V oldV = get(key);
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equals(key)) {
                values.add(i, value);
                return oldV;
            }
        }
        keys.add(key);
        values.add(value);
        return oldV;
    }

    @Override
    public V get(K key) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equals(key))
                return values.get(i);
        }
        return parent.get(key);
    }

    @Override
    public V ref(K key) {
        refcounts.add(key);
        return get(key);
    }
}
