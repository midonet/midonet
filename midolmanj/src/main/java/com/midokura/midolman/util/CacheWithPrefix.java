/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CacheWithPrefix implements Cache {

    private static final Logger log = LoggerFactory
            .getLogger(CacheWithPrefix.class);
    private Cache cache;
    private String prefix;

    public CacheWithPrefix(Cache cache, String prefix) {
        this.cache = cache;
        this.prefix = prefix;
    }

    @Override
    public void set(String key, String value) {
        String pkey = prefix+key;
        log.debug("set - key {} value {}", pkey, value);
        cache.set(pkey, value);
    }

    @Override
    public String get(String key) {
        String pkey = prefix+key;
        log.debug("get - key {}", pkey);
        return cache.get(pkey);
    }

    @Override
    public String getAndTouch(String key) {
        String pkey = prefix+key;
        log.debug("getAndTouch - key {}", pkey);
        return cache.getAndTouch(pkey);
    }

    @Override
    public int getExpirationSeconds() {
        return cache.getExpirationSeconds();
    }

}
