/**
 * TestCacheFactory.java - Test class for CacheFactory.
 *
 * Copyright (c) 2012 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.util;

import org.apache.commons.configuration.HierarchicalConfiguration;
import static org.junit.Assert.*;
import org.junit.Test;

public class TestCacheFactory {

    @Test(expected = CacheException.class, timeout = 5000)
    public void testCacheMemcache() throws Exception {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        config.setProperty("midolman.cache_type", "memcache");
        config.setProperty("memcache.memcache_hosts",
                "192.0.2.4:1211,192.0.2.5:1211");

        Cache cache = CacheFactory.create(config);

        // shouldn't really reach here
        assertTrue(false);

        /*
         * memcache client is expected to raise a timeout exception when it
         * tries to connect to memcached and fails.
         */
    }

    @Test(expected = CacheException.class, timeout = 5000)
    public void testCreateCassandra() throws Exception {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        config.setProperty("midolman.cache_type", "cassandra");
        config.setProperty("cassandra.servers", "localhost:9160");
        config.setProperty("cassandra.cluster", "midonet");
        config.setProperty("cassandra.keyspace", "midolmanj");
        config.setProperty("cassandra.replication_factor", 1);

        Cache cache = CacheFactory.create(config);

        // shouldn't really reach here
        assertTrue(false);
    }

    @Test(expected = CacheException.class)
    public void testCreateCacheInvalid() throws Exception {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        config.setProperty("midolman.cache_type", "nevergonnaexist");

        Cache cache = CacheFactory.create(config);

        // shouldn't really reach here
        assertTrue(false);
    }
}
