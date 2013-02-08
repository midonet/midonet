/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL, Midokura Inc.
 */
package org.midonet.cache;

import org.midonet.util.functors.Callback1;


public class AsyncCacheWithPrefix implements AsyncCache {

    private AsyncCache cache;
    private String prefix;

    public AsyncCacheWithPrefix(AsyncCache cache, String prefix) {
        if (cache == null)
            throw new NullPointerException("underlying cache is null");
        this.cache = cache;
        this.prefix = prefix;
    }

    @Override
    public void set(String key, String value) {
        String pkey = prefix+key;
        cache.set(pkey, value);
    }

    @Override
    public void get(String key, Callback1<String> cb) {
        String pkey = prefix+key;
        cache.get(pkey, cb);
    }

    @Override
    public void getAndTouch(String key, Callback1<String> cb) {
        String pkey = prefix+key;
        cache.getAndTouch(pkey, cb);
    }

    @Override
    public int getExpirationSeconds() {
        return cache.getExpirationSeconds();
    }

}
