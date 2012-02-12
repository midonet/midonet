package com.midokura.midolman;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import org.apache.commons.configuration.HierarchicalConfiguration;

import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.MemcacheCache;

public class TestControllerTrampoline {

    /**
     * Minimum config for constructing ControllerTrampoline in tests.
     */
    protected HierarchicalConfiguration minConfig() {
        HierarchicalConfiguration config = new HierarchicalConfiguration();

        // TODO should not depend so heavily on internals of ControllerTrampoline
        config.setProperty("midolman.midolman_root_key", "/midonet/v1/midolman");
        config.setProperty("openvswitch.midolman_ext_id_key", "midolman-vnet");

        return config;
    }

    @Test(expected = net.spy.memcached.OperationTimeoutException.class,
          timeout = 5000)
    public void testCacheMemcache() throws Exception {
        HierarchicalConfiguration config = minConfig();
        config.setProperty("cache.type", "memcache");
        config.setProperty("memcache.memcache_hosts",
                "192.0.2.4:1211,192.0.2.5:1211");

        ControllerTrampoline trampoline = new ControllerTrampoline(config,
                null, null, null);

        Cache cache = trampoline.createCache();

        // shouldn't really reach here
        assertTrue(cache instanceof MemcacheCache);

        /*
         * memcache client is expceted to raise a timeout exception when it
         * tries to connect to memcached and fails.
         */
    }

    @Test
    public void testCreateCacheInvalid() throws Exception {
        HierarchicalConfiguration config = minConfig();
        config.setProperty("cache.type", "nevergonnaexist");

        ControllerTrampoline trampoline = new ControllerTrampoline(config,
                null, null, null);

        Cache cache = trampoline.createCache();

        assertNull(cache);
    }
}
