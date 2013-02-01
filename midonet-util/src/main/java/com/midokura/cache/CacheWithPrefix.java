/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.cache;


public class CacheWithPrefix implements Cache {

    private Cache cache;
    private String prefix;

    public CacheWithPrefix(Cache cache, String prefix) {
        this.cache = cache;
        this.prefix = prefix;
    }

    @Override
    public void set(String key, String value) {
        String pkey = prefix+key;
        cache.set(pkey, value);
    }

    @Override
    public String get(String key) {
        String pkey = prefix+key;
        return cache.get(pkey);
    }

    @Override
    public String getAndTouch(String key) {
        String pkey = prefix+key;
        return cache.getAndTouch(pkey);
    }

    @Override
    public int getExpirationSeconds() {
        return cache.getExpirationSeconds();
    }

}
