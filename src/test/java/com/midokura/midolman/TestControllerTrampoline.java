package com.midokura.midolman;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import org.apache.commons.configuration.HierarchicalConfiguration;

import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.MemcacheCache;
import com.midokura.midolman.util.VoldemortCache;
import com.midokura.midolman.voldemort.VoldemortTester;

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

    @Test
    public void testCreateEphemeralStoreVoldemort() throws Exception {
        // since this should be only test here to set up Voldemort servers,
        // use try-finally block for tear down

        VoldemortTester voldemort = new VoldemortTester("ephtest", 2000L, 2);
        voldemort.setUp();

        try {
            List<String> urls = voldemort.bootstrapURLs();
            assert urls.size() == 2;
            String servers = urls.get(0) + "," + urls.get(1);

            HierarchicalConfiguration config = minConfig();
            config.setProperty("ephemeral.type", "voldemort");
            config.setProperty("voldemort.lifetime", "2000");
            config.setProperty("voldemort.store", "ephtest");
            config.setProperty("voldemort.servers", servers);

            ControllerTrampoline trampoline = new ControllerTrampoline(config,
                    null, null, null);

            Cache cache = trampoline.createEphemeralStore();

            assertTrue(cache instanceof VoldemortCache);
            assertEquals(2, cache.getExpirationSeconds());
        } finally {
            voldemort.tearDown();
        }
    }

    @Test(expected = net.spy.memcached.OperationTimeoutException.class)
    public void testCreateEphemeralStoreMemcache() throws Exception {
        HierarchicalConfiguration config = minConfig();
        config.setProperty("ephemeral.type", "memcache");
        config.setProperty("memcache.memcache_hosts",
                "192.0.2.4:1211,192.0.2.5:1211");

        ControllerTrampoline trampoline = new ControllerTrampoline(config,
                null, null, null);

        Cache cache = trampoline.createEphemeralStore();

        // shouldn't really reach here
        assertTrue(cache instanceof MemcacheCache);

        /*
         * memcache client is expceted to raise a timeout exception when it
         * tries to connect to memcached and fails.
         */
    }

    @Test
    public void testCreateEphemeralStoreInvalid() throws Exception {
        HierarchicalConfiguration config = minConfig();
        config.setProperty("ephemeral.type", "nevergonnaexist");

        ControllerTrampoline trampoline = new ControllerTrampoline(config,
                null, null, null);

        Cache cache = trampoline.createEphemeralStore();

        assertNull(cache);
    }
}
