/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.util;

import org.junit.Before;
import org.junit.Test;

import org.midonet.cache.Cache;
import org.midonet.cache.MockCache;

public class TestMockCache {

    protected Cache cache;

    final String KEY = "key";
    final String VAL = "val";

    final int NORMAL_EXPIRATION_SECS = 60;
    final int NORMAL_EXPIRATION_MILLIS = NORMAL_EXPIRATION_SECS * 1000;
    final int OVERRIDE_EXPIRATION_SECS = 300;
    final int OVERRIDE_EXPIRATION_MILLIS = OVERRIDE_EXPIRATION_SECS * 1000;

    @Before
    public void setUp() throws Exception {
        cache = new MockCache();
    }

    @Test
    public void testNormal() {
        cache.set(KEY, VAL);
        assertCacheEntry(VAL, NORMAL_EXPIRATION_MILLIS);

        // Check same expiry after getAndTouch
        cache.getAndTouch(KEY);
        assertCacheEntry(VAL, NORMAL_EXPIRATION_MILLIS);
    }

    @Test
    public void testOverrideSet() {
        cache.setWithExpiration(KEY, VAL, OVERRIDE_EXPIRATION_SECS);
        assertCacheEntry(VAL, OVERRIDE_EXPIRATION_MILLIS);

        // Check back to normal expiry after normal getAndTouch
        cache.getAndTouch(KEY);
        assertCacheEntry(VAL, NORMAL_EXPIRATION_MILLIS);
    }

    @Test
    public void testOverrideGetAndTouch() {
        cache.set(KEY, VAL);
        assertCacheEntry(VAL, NORMAL_EXPIRATION_MILLIS);

        // Check changed to longer expiry after overridden getAndTouch
        cache.getAndTouchWithExpiration(KEY, OVERRIDE_EXPIRATION_SECS);
        assertCacheEntry(VAL, OVERRIDE_EXPIRATION_MILLIS);
    }

    @Test
    public void testFullyOverride() {
        cache.setWithExpiration(KEY, VAL, OVERRIDE_EXPIRATION_SECS);
        assertCacheEntry(VAL, OVERRIDE_EXPIRATION_MILLIS);

        // Check stays with longer expiry after overridden getAndTouch
        cache.getAndTouchWithExpiration(KEY, OVERRIDE_EXPIRATION_SECS);
        assertCacheEntry(VAL, OVERRIDE_EXPIRATION_MILLIS);
    }

    private void assertCacheEntry(String value,
                                  int expirationMillis) {
        MockCache mockCache = (MockCache) cache;
        MockCache.CacheEntry entry = mockCache.map.get(KEY);
        assert(entry.value.equals(value));
        assert(entry.expirationMillis == expirationMillis);
    }

}
