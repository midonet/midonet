/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.util.collection;

import java.util.Map;

/**
 * A Map&lt;K, V&gt;-like interface that provides type-safe access to elements
 * and type-safe deletion, to avoid the pitfalls of Map's use of Object as
 * the type of key arguments.
 * <p/>
 * {@link TypedHashMap} is a wrapper around HashMap which implements this
 * interface.
 * <p/>
 * Sample usage:
 * <blockquote><pre>
 * TypedMap&lt;Long, String&gt; map = new TypedHashMap&lt;Long, String&gt;();
 *
 * Long longKey = 10l;
 * int intKey = 10;
 * short shortKey = 10;
 * Short shortObjectKey = 10;
 *
 * map.get(longKey);           // this compiles
 * map.get(intKey);            // this fails at compilation
 * map.get(shortKey);          // this fails at compilation
 * map.get(shortObjectKey);    // this fails at compilation
 * </pre></blockquote><p/>
 */
public interface TypedMap<K, V> {

    V put(K key, V value);

    V get(K key);

    V remove(K key);

    boolean containsKey(K key);

    void clear();

    Map<K, V> viewAsMap();
}
