package com.midokura.midolman.util;

public class CacheWithPrefix implements Cache {

    private Cache cache;
    private String prefix;

    public CacheWithPrefix(Cache cache, String prefix) {
        this.cache = cache;
        this.prefix = prefix;
    }

    @Override
    public void set(String key, String value) {
        cache.set(prefix+key, value);
    }

    @Override
    public String get(String key) {
        return cache.get(prefix+key);
    }

}
