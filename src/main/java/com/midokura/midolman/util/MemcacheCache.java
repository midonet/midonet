package com.midokura.midolman.util;

import java.io.IOException;
import java.util.Date;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.BinaryConnectionFactory;
import net.spy.memcached.CASValue;
import net.spy.memcached.MemcachedClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class MemcacheCache implements Cache {
    private static final Logger log =
                         LoggerFactory.getLogger(MemcacheCache.class);
    
    private MemcachedClient client;
    
    private int expirationSecs;
    
    public MemcacheCache(String memcacheServers, int expirationSecs) 
                throws IOException {
        boolean success = false;
        try {
            client = new MemcachedClient(new BinaryConnectionFactory(),
                                         AddrUtil.getAddresses(memcacheServers));
        
            this.expirationSecs = expirationSecs;

            String testKey = "testkey";
            String testValue = (new Date()).toString();
            set(testKey, testValue);
            String fetchedValue = get(testKey);
            assert testValue.equals(fetchedValue);
            client.delete(testKey);   
            success = true;
        } finally {
            if (!success)
                log.error("Connection to memcached FAILED");
        }
    }

    @Override
    public void set(String key, String value) {
        client.set(key, expirationSecs, value);
    }

    @Override
    public String get(String key) {
        return (String) client.get(key);
    }

    @Override
    public String getAndTouch(String key) {
        CASValue<Object> val = client.getAndTouch(key, expirationSecs);
        return null == val ? null : (String) val.getValue();
        /*String val = (String) client.get(key);
        if (null != val)
            client.set(key, expirationSecs, val);
        return val;
        */
    }

    @Override
    public int getExpirationSeconds() {
        return expirationSecs;
    }
}
