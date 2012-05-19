/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.util;

/**
 * A cache whose client has no control over the values stored in the cache
 * except to prevent their expiration by 'pinning' the corresponding key.
 *
 * @param <K> The type of the key used by the cache.
 * @param <V> The type of the value used by the cache.
 */
public interface ReadCache<K, V> {
    /**
     * Retrieve the value associated with the given key, if any. The cache
     * may automatically load the value or already have pre-loaded values.
     *
     * @param key The key whose corresponding value is requested.
     * @return The value associated with they key, if it has not expired.
     */
    V get(K key);

    /**
     * Prevent a key/value pair from being expired from the cache. A key may
     * be pinned before its value has been loaded into the cache.
     *
     * @param key The key whose value should not be expired.
     */
    void pin(K key);

    /**
     * Allow a key/value pair to expire. If the key is not already Pinned, this
     * operation has no effect. The key may be expired any time after this call
     * depending on the implementation's contract.
     *
     * @param key The key whose value is allowed to expire.
     */
    void unPin(K key);
}
