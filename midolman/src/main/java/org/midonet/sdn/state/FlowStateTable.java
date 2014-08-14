package org.midonet.sdn.state;

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
    <U> U expireIdleEntries(U seed, FlowStateTable.Reducer<K, V, U> func);

    /**
     * Removes a key, returns the previous value associated with it.
     */
    V remove(K key);

    /**
     * Folds the entries of this transaction using the specified Reducer.
     */
    <U> U fold(U seed, Reducer<? super K, ? super V, U> func);

    public interface Reducer<K, V, U> {
        U apply(U acc, K key, V value);
    }
}
