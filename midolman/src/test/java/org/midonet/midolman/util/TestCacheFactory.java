/**
 * TestCacheFactory.java - Test class for CacheFactory.
 *
 * Copyright (c) 2012 Midokura KK. All rights reserved.
 */

package org.midonet.midolman.util;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertTrue;


import org.midonet.cache.CacheException;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.CacheFactory;
import org.midonet.midolman.config.MidolmanConfig;

public class TestCacheFactory {

    @Test(expected = CacheException.class, timeout = 5000)
    public void testUnknownCacheType() throws Exception {

        final HierarchicalConfiguration configStore = new HierarchicalConfiguration();
        configStore.setProperty("midolman.cache_type", "bozo_cache");

        MidolmanConfig config =
            ConfigProvider
                .providerForIniConfig(configStore)
                .getConfig(MidolmanConfig.class);

        CacheFactory.create(config, "unittest");

        // shouldn't really reach here
        assertTrue(false);
    }

    @Test(expected = CacheException.class, timeout = 5000)
    public void testCreateCassandra() throws Exception {

        final HierarchicalConfiguration hierarhicalConfig = new HierarchicalConfiguration();
        hierarhicalConfig.setProperty("midolman.cache_type", "cassandra");
        hierarhicalConfig.setProperty("cassandra.servers", "localhost:9165");
        hierarhicalConfig.setProperty("cassandra.cluster", "newCluster");
        hierarhicalConfig.setProperty("cassandra.keyspace", "midolman");
        hierarhicalConfig.setProperty("cassandra.replication_factor", 1);

        CacheFactory.create(
            ConfigProvider.providerForIniConfig(hierarhicalConfig)
                          .getConfig(MidolmanConfig.class),
            "unittest");

        // shouldn't really reach here
        assertTrue(false);
    }

    @Test(expected = CacheException.class)
    public void testCreateCacheInvalid() throws Exception {
        final HierarchicalConfiguration hierarhicalConfig = new HierarchicalConfiguration();
        hierarhicalConfig.setProperty("midolman.cache_type", "nevergonnaexist");


        CacheFactory.create(
            ConfigProvider.providerForIniConfig(hierarhicalConfig)
                          .getConfig(MidolmanConfig.class),
            "unittest");

        // shouldn't really reach here
        assertTrue(false);
    }
}
