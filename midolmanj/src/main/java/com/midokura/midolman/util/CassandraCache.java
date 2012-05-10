/**
 * CassandraCache.java - A Cache class backed by Cassandra.
 *
 * Copyright (c) 2012 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.util;

import java.net.InetSocketAddress;
import java.util.Date;
import java.util.List;

import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.cassandra.service.FailoverPolicy;
import me.prettyprint.hector.api.Cluster;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.mutation.Mutator;
import me.prettyprint.hector.api.query.ColumnQuery;
import me.prettyprint.hector.api.query.QueryResult;
import me.prettyprint.hector.api.ddl.ColumnFamilyDefinition;
import me.prettyprint.hector.api.ddl.ComparatorType;
import me.prettyprint.hector.api.ddl.KeyspaceDefinition;
import me.prettyprint.hector.api.exceptions.HectorException;

import org.apache.cassandra.locator.SimpleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class CassandraCache implements Cache {
    private static final Logger log =
                         LoggerFactory.getLogger(CassandraCache.class);

    private int expirationSecs;
    private Cluster cluster;
    private Keyspace keyspace;
    private String columnFamily;
    private final String column = "target";

    private static StringSerializer ss = StringSerializer.get();

    public CassandraCache(String servers, String clusterName,
                          String keyspaceName, String columnFamily,
                          int replicationFactor, int expirationSecs)
            throws HectorException {
        boolean success = false;

        try {
            this.cluster = HFactory.getOrCreateCluster(clusterName, servers);
            // Using FAIL_FAST because if Hector blocks the operations too
            // long, midolmanj gets disconnected from OVS and crashes.
            this.keyspace = HFactory.createKeyspace(
                keyspaceName, cluster,
                HFactory.createDefaultConsistencyLevelPolicy(),
                FailoverPolicy.FAIL_FAST);
            this.columnFamily = columnFamily;
            this.expirationSecs = expirationSecs;

            log.debug("Check column family {} exists in keyspace {}",
                      columnFamily, keyspace);

            KeyspaceDefinition ksDef = cluster.describeKeyspace(keyspaceName);
            if (ksDef == null) {
                log.info("Creating keyspace {} in cluster {}",
                         keyspaceName, clusterName);
                ksDef = HFactory.createKeyspaceDefinition(
                    keyspaceName, SimpleStrategy.class.getName(),
                    replicationFactor, null);
                cluster.addKeyspace(ksDef, true);
                ksDef = cluster.describeKeyspace(keyspaceName);
            }

            for (ColumnFamilyDefinition cfDef : ksDef.getCfDefs()) {
                if (cfDef.getName().equals(columnFamily)) {
                    log.debug("Column family {} found in keyspace {}",
                              columnFamily, keyspaceName);
                    success = true;
                    return;
                }
            }

            // Create a column family if it doesn't exist.
            log.info("Creating column family {}", columnFamily);
            ColumnFamilyDefinition cfDef =
                HFactory.createColumnFamilyDefinition(
                    keyspaceName, columnFamily, ComparatorType.BYTESTYPE);
            cfDef.setKeyValidationClass("BytesType");
            cfDef.setDefaultValidationClass("BytesType");
            cluster.addColumnFamily(cfDef, true);

            success = true;
        } finally {
            if (!success) {
                log.error("Connection to Cassandra FAILED");
            }
        }
    }

    @Override
    public void set(String key, String value) {
        try {
            Mutator<String> mutator = HFactory.createMutator(keyspace, ss);
            mutator.insert(key, columnFamily,
                           HFactory.createColumn(column, value, expirationSecs,
                                                 ss, ss));
            mutator.execute();
        } catch (HectorException e) {
            log.error("set failed", e);
        }
    }

    @Override
    public String get(String key) {
        HColumn<String, String> result = null;
        try {
            ColumnQuery<String, String, String> query =
                HFactory.createColumnQuery(keyspace, ss, ss, ss);
            query.setColumnFamily(columnFamily).setKey(key).setName("target");
            result = query.execute().get();
        } catch (HectorException e) {
            log.error("get failed", e);
            return null;
        }

        if (result == null) {
            return null;
        }
        String value = result.getValue();
        return (value.length() != 0) ? value : null;
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
        return expirationSecs;
    }
}
