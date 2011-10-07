package com.midokura.midolman.util;

import java.io.IOException;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.BinaryConnectionFactory;
import net.spy.memcached.CASValue;
import net.spy.memcached.MemcachedClient;

public class MemcacheCache implements Cache {
    
    private MemcachedClient client;
    
    private int expirationSecs;
    
    public MemcacheCache(String memcacheServers, int expirationSecs) throws IOException {
        client = new MemcachedClient(new BinaryConnectionFactory(),
                AddrUtil.getAddresses(memcacheServers));
        
        this.expirationSecs = expirationSecs;
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
        /* TODO(pino): revert to using getAndTouch single operation after
         * packaging latest Memcached daemon (1.4.8) for debian. Our servers
         * run 1.4.5, which doesn't recognize this operation.
         *CASValue<Object> val = client.getAndTouch(key, expirationSecs);
         *return null == val ? null : (String) val.getValue();
         */
        String val = (String) client.get(key);
        if (null != val)
            client.touch(key, expirationSecs);
        return val;
    }

    @Override
    public int getExpirationSeconds() {
        return expirationSecs;
    }
}
