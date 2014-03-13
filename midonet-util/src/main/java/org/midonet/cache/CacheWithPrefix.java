/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.cache;

import java.util.List;
import java.util.Map;

public class CacheWithPrefix implements Cache {

    private Cache cache;
    private String prefix;

    public CacheWithPrefix(Cache cache, String prefix) {
        if (cache == null)
            throw new NullPointerException("Underlying cache is null");
        this.cache = cache;
        this.prefix = prefix;
    }

    @Override
    public void set(String key, String value) {
        String pkey = prefix+key;
        cache.set(pkey, value);
    }

    @Override
    public void setWithExpiration(String key, String value,
                                  int overrideExpirationSeconds) {
        String pkey = prefix+key;
        cache.setWithExpiration(pkey, value, overrideExpirationSeconds);
    }

    @Override
    public String get(String key) {
        String pkey = prefix+key;
        return cache.get(pkey);
    }

    @Override
    public void delete(String key) {
        String pkey = prefix+key;
        cache.delete(pkey);
    }

    @Override
    public Map<String, String> dump(int maxEntries) {
        return(cache.dump(maxEntries));
    }

    @Override
    public String getAndTouch(String key) {
        return getAndTouchWithExpiration(key, cache.getExpirationSeconds());
    }

    @Override
    public String getAndTouchWithExpiration(String key, int expirationSeconds) {
        String pkey = prefix+key;
        return cache.getAndTouchWithExpiration(pkey, expirationSeconds);
    }

    @Override
    public int getExpirationSeconds() {
        return cache.getExpirationSeconds();
    }

}
