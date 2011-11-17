/*
 * Copyright 2011 Midokura KK
 */

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
public class TestVoldemortCache extends TestCache {

    private VoldemortTester voldemort;
    private VoldemortServer[] servers;

    @Before
    public void setUp() throws Exception {
        voldemort = new VoldemortTester();
        voldemort.setUp();
        lifetime = voldemort.lifetime();
        servers = voldemort.servers();
        cache = voldemort.constructCache();
    }

    @After
    public void tearDown() throws Exception {
        voldemort.tearDown();
        cache = null;
        servers = null;
        voldemort = null;
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

    @Test
    public void testRefreshWithSingleServerDeath() throws Exception {
        // set many keys in hopes that one will be located in dead server
        for (int i = 0; i < 24; i++)
            cache.set("key-" + i, "value-" + i);

        for (int i = 0; i < 12; i++) {
            Thread.sleep(lifetime / 4);
            for (int j = 0; j < 24; j++)
                assertEquals("value-" + j, cache.getAndTouch("key-" + j));
        }

        voldemort.servers()[0].stop();

        for (int i = 0; i < 24; i++) {
            Thread.sleep(lifetime / 4);
            for (int j = 0; j < 24; j++)
                assertEquals("value-" + j, cache.getAndTouch("key-" + j));
        }

        Thread.sleep(lifetime / 4);
        for (int i = 0; i < 24; i++)
            assertEquals("value-" + i, cache.get("key-" + i));
    }

}
