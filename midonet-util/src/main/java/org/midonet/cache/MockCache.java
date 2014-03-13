/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.cache;

import java.util.HashMap;
import java.util.Map;

import org.midonet.util.eventloop.Reactor;

public class MockCache implements Cache {

    public static class CacheEntry {
        public String value;
        public long timeExpiredMillis;
        public int expirationMillis;
    }

    public Map<String, CacheEntry> map = new HashMap<String, CacheEntry>();
    private Reactor reactor;
    private int expirationMillis;

    public MockCache() {
        reactor = null;
        expirationMillis = 60 * 1000;
    }

    public MockCache(Reactor reactor, int expirationSeconds) {
        this.reactor = reactor;
        this.expirationMillis = expirationSeconds * 1000;
    }

    @Override
    public void set(String key, String value) {
        setWithExpiration(key, value, getExpirationSeconds());
    }

    @Override
    public void setWithExpiration(String key, String value,
                                  int overrideExpirationSeconds) {
        int overrideExpirationMillis = overrideExpirationSeconds * 1000;

        CacheEntry entry = map.get(key);
        if (null == entry) {
            entry = new CacheEntry();
            map.put(key, entry);
        }
        entry.value = value;
        entry.timeExpiredMillis = (null == reactor) ? 0 :
                reactor.currentTimeMillis() + overrideExpirationMillis;
        entry.expirationMillis = overrideExpirationMillis;
    }

    @Override
    public String get(String key) {
        return get(key, false);
    }

    /*
     * Needed for compilation (Interface Cache has this declared)
     */
    @Override
    public Map<String, String> dump(int maxEntries) {
        // does nothing
        return null;
    }

    /*
     * Needed for compilation (Interface Cache has this declared)
     */
    @Override
    public void delete(String key) {
        // does nothing
        return;
    }

    @Override
    public String getAndTouch(String key) {
        return get(key, true);
    }

    @Override
    public String getAndTouchWithExpiration(String key, int expirationSeconds) {
        return get(key, true, expirationSeconds);
    }

    public void clear() {
        map.clear();
    }

    private String get(String key, boolean refresh) {
        return get(key, refresh, getExpirationSeconds());
    }

    private String get(String key, boolean refresh, int expirationSeconds) {
        CacheEntry entry = map.get(key);
        if (null == entry)
            return null;
        if (refresh)
            entry.expirationMillis = (expirationSeconds * 1000);
        if (null == reactor)
            return entry.value;
        long now = reactor.currentTimeMillis();
        if (entry.timeExpiredMillis <= now) {
            map.remove(key);
            return null;
        }
        if (refresh)
            entry.timeExpiredMillis = now + (expirationSeconds * 1000);
        return entry.value;
    }

    public Long getExpireTimeMillis(String key) {
        CacheEntry entry = map.get(key);
        if (null == entry || null == reactor)
            return null;
        return entry.timeExpiredMillis;
    }

    @Override
    public int getExpirationSeconds() {
        return expirationMillis / 1000;
    }
}
