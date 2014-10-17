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

package org.midonet.cache;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.midonet.util.eventloop.Reactor;

/**
 * An abstract implementation of {@link ReadCache} that delegates to its
 * subclass:
 * 1) loading values when the cache misses.
 * 2) removing stale/expired entries.
 * 3) maintaining freshness/consistency of values in the cache.
 *
 * LoadingCache tracks for each key the time of the last call to get(). The last
 * access time may be used by a subclass to decide whether to expire an entry.
 *
 * @param <K> The type of the key used by the cache.
 * @param <V> The type of the value used by the cache.
 */
public abstract class LoadingCache<K, V> implements ReadCache<K, V> {
    private Map<K, V> map = new HashMap<K,V>();
    private Map<K, Long> lastAccessTimes = new HashMap<K,Long>();
    private Set<K> pinnedKeys = new HashSet<K>();
    protected Reactor reactor;

    public LoadingCache(Reactor reactor) {
        this.reactor = reactor;
    }

    /**
     * Must be implemented by non-abstract subclasses. On get, if this loading
     * cache misses, it will call the load method to generate the value.
     *
     * @param key The key whose value should be generated.
     * @return The value corresponding to the key, to populate the cache.
     */
    protected abstract V load(K key);

    @Override
    public final V get(K key) {
        V value = map.get(key);
        if (null == value) {
            value = load(key);
            if (null == value)
                return null;
            map.put(key, value);
        }
        lastAccessTimes.put(key, reactor.currentTimeMillis());
        return value;
    }

    @Override
    public final void pin(K key) {
        pinnedKeys.add(key);
    }

    @Override
    public final void unPin(K key) {
        pinnedKeys.remove(key);
    }

    public final boolean hasKey(K key) {
        return map.containsKey(key);
    }

    public final boolean isPinned(K key) {
        return pinnedKeys.contains(key);
    }

    public final Long getLastAccessTime(K key) {
        return lastAccessTimes.get(key);
    }

    /**
     * Put or replace a cache key/value entry. This is only intended to be used
     * by the subclass.
     *
     * @param key The new or replaced cache entry's key. Must not be null.
     * @param value The new or replaced cache entry's value. May be null, in
     *              which case the key/value entry is removed from the cache.
     */
    protected final void put(K key, V value) {
        if (null == value) {
            pinnedKeys.remove(key);
            lastAccessTimes.remove(key);
            map.remove(key);
        }
        else {
            V oldValue = map.put(key, value);
            if (null == oldValue)
                lastAccessTimes.put(key, reactor.currentTimeMillis());
        }
    }
}
