package com.midokura.midolman.util;

import java.util.HashMap;
import java.util.Map;

import com.midokura.midolman.eventloop.Reactor;

public class MockCache implements Cache {

    private static class CacheEntry {
        String value;
        long timeExpiredMillis;
    }

    public Map<String, CacheEntry> map = new HashMap<String, CacheEntry>();
    private Reactor reactor;
    private int expirationMillis;

    public MockCache() {
        reactor = null;
        expirationMillis = 0;
    }

    public MockCache(Reactor reactor, int expirationSeconds) {
        this.reactor = reactor;
        this.expirationMillis = expirationSeconds * 1000;
    }

    @Override
    public void set(String key, String value) {
        CacheEntry entry = map.get(key);
        if (null == entry) {
            entry = new CacheEntry();
            map.put(key, entry);
        }
        entry.value = value;
        entry.timeExpiredMillis = null == reactor ? 0 : reactor
                .currentTimeMillis() + expirationMillis;
    }

    @Override
    public String get(String key) {
        return get(key, false);
    }

    @Override
    public String getAndTouch(String key) {
        return get(key, true);
    }

    private String get(String key, boolean refresh) {
        CacheEntry entry = map.get(key);
        if (null == entry)
            return null;
        if (null == reactor)
            return entry.value;
        long now = reactor.currentTimeMillis();
        if (entry.timeExpiredMillis <= now) {
            map.remove(key);
            return null;
        }
        if (refresh)
            entry.timeExpiredMillis = now + expirationMillis;
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
