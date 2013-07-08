/**
 * CassandraCache.java - A Cache class backed by Cassandra.
 *
 * Copyright (c) 2012 Midokura KK. All rights reserved.
 */

package org.midonet.cassandra;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import me.prettyprint.hector.api.exceptions.HectorException;

import org.midonet.cache.Cache;


public class CassandraCache implements Cache {

    private final String column = "target";
    private CassandraClient client;

    public CassandraCache(String servers, int maxActiveConns,
                          String clusterName, String keyspaceName,
                          String columnFamily, int replicationFactor,
                          int expirationSecs)
            throws HectorException {
        client = new CassandraClient(servers, maxActiveConns, clusterName,
                                     keyspaceName, columnFamily,
                                     replicationFactor, expirationSecs);
        client.connect();
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
    public void delete(String key) {
        client.delete(key, column);
    }

    @Override
    public Map<String, String> dump(int maxEntries) {
        // here we assume key <-> value mapping is one to one
        Map<String, String> retMap = new HashMap<String, String>();
        Map<String, List<String>> origMap = client.dumpTable(null, null, 
                                    String.class, maxEntries, maxEntries);
        if (origMap.isEmpty() == false) {
            Iterator<String> it = origMap.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                List<String> columns = origMap.get(key);

                for (String column : columns) {
                    retMap.put(key, column);
                }
            }
        }
        return retMap;
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
