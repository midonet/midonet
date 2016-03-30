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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;

import rx.Observable;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

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
public abstract class LoadingCache<K, V> {
    private ConcurrentMap<K, V> map = new ConcurrentHashMap<>();
    private ConcurrentMap<K, Long> lastAccessTimes
        = new ConcurrentHashMap<>();
    protected Reactor reactor;

    public LoadingCache(Reactor reactor) {
        this.reactor = reactor;
    }

    /**
     * Must be implemented by non-abstract subclasses. On get, if this loading
     * cache misses, it will call the load method to generate the value.
     *
     * @param key The key whose value should be generated.
     * @return An observable which will steam the value corresponding to the key
     *         once it has been loaded.
     */
    protected abstract Observable<V> load(K key);

    /**
     * Synchronous version of load
     */
    protected abstract V loadSync(K key);

    public final Observable<V> get(final K key) {
        V value = map.get(key);
        if (null == value) {
            return load(key)
                .observeOn(Schedulers.trampoline())
                .doOnNext(new Action1<V>() {
                        @Override
                        public void call(V value) {
                            put(key, value);
                            lastAccessTimes.put(key,
                                    reactor.currentTimeMillis());
                        }
                    });
        }
        lastAccessTimes.put(key, reactor.currentTimeMillis());
        return Observable.just(value);
    }

    public final V getSync(K key) {
        V value = map.get(key);
        if (null == value) {
            value = loadSync(key);
            if (null == value)
                return null;
            map.put(key, value);
        }
        lastAccessTimes.put(key,
                reactor.currentTimeMillis());
        return value;
    }

    public final boolean hasKey(K key) {
        return map.containsKey(key);
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
            lastAccessTimes.remove(key);
            map.remove(key);
        } else {
            V oldValue = map.put(key, value);
            if (null == oldValue)
                lastAccessTimes.put(key, reactor.currentTimeMillis());
        }
    }

}
