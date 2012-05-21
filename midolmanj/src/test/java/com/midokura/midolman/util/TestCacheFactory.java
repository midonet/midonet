/**
 * TestCacheFactory.java - Test class for CacheFactory.
 *
 * Copyright (c) 2012 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.util;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

import com.midokura.config.ConfigProvider;
import com.midokura.midolman.config.MidolmanConfig;

public class TestCacheFactory {

    @Test(expected = CacheException.class, timeout = 5000)
    public void testCacheMemcache() throws Exception {

        final HierarchicalConfiguration configStore = new HierarchicalConfiguration();
        configStore.setProperty("midolman.cache_type", "memcache");
        configStore.setProperty("memcache.memcache_hosts",
                                "192.0.2.4:1211,192.0.2.5:1211");

        MidolmanConfig config =
            ConfigProvider
                .providerForIniConfig(configStore)
                .getConfig(MidolmanConfig.class);

        CacheFactory.create(config);

        // shouldn't really reach here
        assertTrue(false);

        /*
         * memcache client is expected to raise a timeout exception when it
         * tries to connect to memcached and fails.
         */
    }

    @Test(expected = CacheException.class, timeout = 5000)
    public void testCreateCassandra() throws Exception {

        final HierarchicalConfiguration hierarhicalConfig = new HierarchicalConfiguration();
        hierarhicalConfig.setProperty("midolman.cache_type", "cassandra");
        hierarhicalConfig.setProperty("cassandra.servers", "localhost:9160");
        hierarhicalConfig.setProperty("cassandra.cluster", "midonet");
        hierarhicalConfig.setProperty("cassandra.keyspace", "midolmanj");
        hierarhicalConfig.setProperty("cassandra.replication_factor", 1);

        CacheFactory.create(
            ConfigProvider.providerForIniConfig(hierarhicalConfig)
                          .getConfig(MidolmanConfig.class));

        // shouldn't really reach here
        assertTrue(false);
    }

    @Test(expected = CacheException.class)
    public void testCreateCacheInvalid() throws Exception {
        final HierarchicalConfiguration hierarhicalConfig = new HierarchicalConfiguration();
        hierarhicalConfig.setProperty("midolman.cache_type", "nevergonnaexist");


        CacheFactory.create(
            ConfigProvider.providerForIniConfig(hierarhicalConfig)
                          .getConfig(MidolmanConfig.class));

        // shouldn't really reach here
        assertTrue(false);
    }
}
