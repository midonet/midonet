package com.midokura.midolman.util;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.voldemort.VoldemortTester;

import voldemort.server.VoldemortServer;

/**
 * Test VoldemortCache.
 *
 * @author Yoo Chung
 */
public class TestVoldemortCache {

    private VoldemortTester voldemort;
    private long lifetime;
    private VoldemortServer[] servers;

    /*
     * The actual tests for VoldemortCache are below.
     * The stuff above are for setting up and tearing down tests,
     * either for the tests here or for other tests which wish to
     * use Voldemort servers.
     */

    private VoldemortCache cache;

    @Before
    public void setUp() throws Exception {
        voldemort = new VoldemortTester();
        voldemort.setUp();
        cache = voldemort.constructCache();
        lifetime = voldemort.lifetime();
        servers = voldemort.servers();
    }

    @After
    public void tearDown() throws Exception {
        cache = null;
        voldemort.tearDown();
    }

    @Test
    public void testGetMissing() {
        assertNull(cache.get("test_key"));
    }

    @Test
    public void testSet() {
        cache.set("test_key", "test_value");
    }

    @Test
    public void testSetAndGet() {
        cache.set("test_key", "test_value");
        assertEquals("test_value", cache.get("test_key"));
    }

    @Test
    public void testSetReplace() {
        cache.set("test_key", "test_value1");
        cache.set("test_key", "test_value2");
        assertEquals("test_value2", cache.get("test_key"));
    }

    @Test
    public void testExpireSingle() throws Exception {
        cache.set("test_key", "test_value");
        Thread.sleep(3 * lifetime);
        assertNull(cache.get("test_key"));
    }

    @Test
    public void testRefresh() throws Exception {
        cache.set("test_key", "test_value");

        // repeat refreshes until at least 3*lifetime
        // if refresh does not work, this will ensure expiration
        for (int i = 0; i < 12; i++) {
            Thread.sleep(lifetime / 2);
            assertEquals("test_value", cache.getAndTouch("test_key"));
        }

        Thread.sleep(lifetime / 2);
        assertEquals("test_value", cache.get("test_key"));
    }

    @Test
    public void testSingleServerDeath() {
        for (int i = 0; i < 20; i++) {
            cache.set("key-" + i, "value-" + i);
        }

        servers[0].stop();

        for (int i = 0; i < 20; i++) {
            assertEquals("value-" + i, cache.get("key-" + i));
        }

        for (int i = 0; i < 20; i++) {
            cache.set("key-" + i, "value-" + (i + 100));
        }

        for (int i = 0; i < 20; i++) {
            assertEquals("value-" + (i + 100), cache.get("key-" + i));
        }
    }

}
