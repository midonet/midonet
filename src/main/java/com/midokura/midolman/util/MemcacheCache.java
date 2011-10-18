package com.midokura.midolman.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.List;

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
            List<InetSocketAddress> addresses = AddrUtil.getAddresses(memcacheServers);
            if (addresses.size() != 1) {
                log.error("Multiple memcached servers specified");
                throw new RuntimeException("Midolman only supports using a " +
                                           "single memcached server");
                // This is because memcached requires that clients ensure
                // consistency of writes themselves when using multiple servers,
                // and we don't.  So we have this restriction to avoid situations
                // like write a NAT mapping to mc #1, look it up later on mc #2
                // and don't find it, so write a new inconsistent NAT mapping.
            }
            client = new MemcachedClient(new BinaryConnectionFactory(),
                                         addresses);
        
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
