/**
 * CacheFactory.java - Constructs Cache objects.
 *
 * Copyright (c) 2012 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.util;

import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.config.MidolmanConfig;

public class CacheFactory {
    private static final Logger log =
        LoggerFactory.getLogger(CacheFactory.class);

    public static final int CACHE_EXPIRATION_SECONDS = 60;

    /**
     * Create a Cache object.
     *
     * @param config configuration for the Cache object
     * @return a Cache object
     * @throws CacheException if an error occurs
     */
    public static Cache create(MidolmanConfig config)
        throws CacheException {
        Cache cache = null;
        String cacheType = config.getMidolmanCacheType();

        boolean isValid = false;

        try {
            if (cacheType.equals("memcache")) {
                isValid = true;
                // set log4j logging for spymemcached client
                Properties props = System.getProperties();
                props.put("net.spy.log.LoggerImpl",
                          "net.spy.memcached.compat.log.Log4JLogger");
                System.setProperties(props);

                String memcacheHosts = config.getMemcacheHosts();

                cache = new MemcacheCache(memcacheHosts,
                                          CACHE_EXPIRATION_SECONDS);
            } else if (cacheType.equals("cassandra")) {
                isValid = true;
                String servers = config.getCassandraServers();
                String cluster = config.getCassandraCluster();
                String keyspace = config.getCassandraMidonetKeyspace();

                int replicationFactor = config.getCassandraReplicationFactor();

                cache = new CassandraCache(servers, cluster, keyspace,
                                           "nat", replicationFactor,
                                           CACHE_EXPIRATION_SECONDS);
            }
        } catch (Exception e) {
            throw new CacheException("error while creating cache", e);
        }

        if (!isValid) {
            throw new CacheException("unknown cache type");
        }

        return cache;
    }
}
