/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.collections;

import java.util.HashMap;

/**
 * Default implementation of a {@link TypedMap}. See {@link TypedMap} for a
 * example of the intended usage.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/18/12
 */
public class TypedHashMap<K, V> extends HashMap<K, V>
    implements TypedMap<K, V> {

    @Override
    public V getTyped(K key) {
        return get(key);
    }

    @Override
    public V removeTyped(K key) {
        return remove(key);
    }

    @Override
    public boolean containsKeyTyped(K key) {
        return containsKey(key);
    }
}
