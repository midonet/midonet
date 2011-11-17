/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.util;

import static org.junit.Assert.*;

import org.junit.Test;

public abstract class TestCache {

    /** Minimum lifetime of items in milliseconds. */
    protected long lifetime;

    /** The cache to test. */
    protected Cache cache;

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

}
