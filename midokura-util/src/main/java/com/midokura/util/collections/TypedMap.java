/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.collections;

import java.util.Map;

/**
 * A Map&lt;K, V&gt; specialization that provided typed accessor to elements and
 * typed deletion method for a key. Also this map can always be used as a proper
 * map but then you lose the enforcing of the key type.
 * <p/>
 * It needs to be used in conjunction with {@link TypedHashMap}.
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
 * map.getTyped(longKey);           // this compiles
 * map.getTyped(intKey);            // this fails at compilation
 * map.getTyped(shortKey);          // this fails at compilation
 * map.getTyped(shortObjectKey);    // this fails at compilation
 * </pre></blockquote><p/>
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/18/12
 */
public interface TypedMap<K, V> extends Map<K, V> {

    V getTyped(K key);

    V removeTyped(K key);

    boolean containsKeyTyped(K key);

}
