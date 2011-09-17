package com.midokura.midolman.util;

import java.io.IOException;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.BinaryConnectionFactory;
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

}
