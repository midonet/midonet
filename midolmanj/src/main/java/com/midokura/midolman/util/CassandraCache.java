/**
 * CassandraCache.java - A Cache class backed by Cassandra.
 *
 * Copyright (c) 2012 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.util;

import me.prettyprint.hector.api.exceptions.HectorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class CassandraCache implements Cache {
    private static final Logger log =
                         LoggerFactory.getLogger(CassandraCache.class);

    private final String column = "target";
    private CassandraClient client;

    public CassandraCache(String servers, String clusterName,
                          String keyspaceName, String columnFamily,
                          int replicationFactor, int expirationSecs)
        throws HectorException {
        client = new CassandraClient(servers, clusterName, keyspaceName, columnFamily, replicationFactor, expirationSecs);
    }

    @Override
    public void set(String key, String value) {
        client.set(key, value, column);
    }

    @Override
    public String get(String key) {
        return client.get(key, column);
    }


    @Override
    public String getAndTouch(String key) {
        // Horrible but seems to be the only way because batch doesn't
        // accept a query.
        String value = this.get(key);
        if (value == null) {
            return null;
        }
        this.set(key, value);
        return value;
    }

    @Override
    public int getExpirationSeconds() {
        return client.getExpirationSeconds();
    }
}
