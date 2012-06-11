/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.util;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.cassandra.service.FailoverPolicy;
import me.prettyprint.hector.api.Cluster;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.beans.ColumnSlice;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.beans.Row;
import me.prettyprint.hector.api.beans.Rows;
import me.prettyprint.hector.api.ddl.ColumnFamilyDefinition;
import me.prettyprint.hector.api.ddl.ComparatorType;
import me.prettyprint.hector.api.ddl.KeyspaceDefinition;
import me.prettyprint.hector.api.exceptions.HInvalidRequestException;
import me.prettyprint.hector.api.exceptions.HectorException;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.mutation.Mutator;
import me.prettyprint.hector.api.query.ColumnQuery;
import me.prettyprint.hector.api.query.MultigetSliceQuery;
import me.prettyprint.hector.api.query.QueryResult;
import me.prettyprint.hector.api.query.SliceQuery;
import org.apache.cassandra.locator.SimpleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class CassandraClient {

    private static final Logger log =
            LoggerFactory.getLogger(CassandraClient.class);

    private int expirationSecs;
    private Cluster cluster;
    private Keyspace keyspace;
    private String columnFamily;

    private static StringSerializer ss = StringSerializer.get();

    public CassandraClient(String servers, String clusterName,
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
                try {
                    cluster.addKeyspace(ksDef, true);
                } catch (HInvalidRequestException e) {
                    log.info("Someone beat us to creating keyspace {}",
                             keyspaceName);
                }
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
                            keyspaceName, columnFamily,
                            ComparatorType.BYTESTYPE);
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

    public void set(String key, String value, String newColumn) {
        try {
            Mutator<String> mutator = HFactory.createMutator(keyspace, ss);

            mutator.insert(key, columnFamily,
                           HFactory.createColumn(newColumn, value,
                                                 expirationSecs,
                                                 ss, ss));
            mutator.execute();
        } catch (HectorException e) {
            log.error("set failed", e);
        }
    }

    public String get(String key, String columnName) {
        HColumn<String, String> result = null;
        try {
            ColumnQuery<String, String, String> query =
                    HFactory.createColumnQuery(keyspace, ss, ss, ss);
            query.setColumnFamily(columnFamily).setKey(key).setName(columnName);
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

    public <T> Map<String, T> executeSliceQuery(String key, String startRange,
                                                String endRange,
                                                Class<T> returnValueClass,
                                                int maxResultItems) {
        Map<String, T> res = new HashMap<String, T>();
        try {
            SliceQuery<String, String, String> sliceQuery = HFactory.createSliceQuery(
                    keyspace, ss, ss, ss);
            sliceQuery.setColumnFamily(columnFamily);
            sliceQuery.setKey(key);
            sliceQuery.setRange(startRange, endRange, false, maxResultItems);

            QueryResult<ColumnSlice<String, String>> result = sliceQuery.execute();
            for (HColumn<String, String> entry : result.get().getColumns()) {
                T value = convertSafely(returnValueClass,
                                        entry.getValue());
                res.put(entry.getName(), value);
            }
        } catch (HectorException e) {
            log.error("slice query failed", e);
        }
        return res;
    }

    public <T> Map<String, T> executeSliceQuery(List<String> keys,
                                                String startRange,
                                                String endRange,
                                                Class<T> returnValueClass,
                                                int maxResultItems) {
        Map<String, T> res = new HashMap<String, T>();
        try {
            MultigetSliceQuery<String, String, String> sliceQuery = HFactory.createMultigetSliceQuery(
                    keyspace, ss, ss, ss);
            sliceQuery.setColumnFamily(columnFamily);
            sliceQuery.setKeys(keys);
            sliceQuery.setRange(startRange, endRange, false, maxResultItems);
            QueryResult<Rows<String, String, String>> result = sliceQuery.execute();
            for (Row<String, String, String> row : result.get()) {
                for (HColumn<String, String> entry : row.getColumnSlice()
                                                        .getColumns()) {
                    T value = convertSafely(returnValueClass,
                                            entry.getValue());
                    res.put(entry.getName(), value);
                }
            }
        } catch (HectorException e) {
            log.error("multiget slice query failed", e);
        }
        return res;
    }

    public <T> List<T> getAllColumnsValues(String key,
                                           Class<T> returnValueClass,
                                           int maxResultItems) {
        List<T> res = new ArrayList<T>();
        try {
            SliceQuery<String, String, String> sliceQuery = HFactory.createSliceQuery(
                    keyspace, ss, ss, ss);
            sliceQuery.setColumnFamily(columnFamily);
            sliceQuery.setKey(key);
            sliceQuery.setRange(null, null, false, maxResultItems);

            QueryResult<ColumnSlice<String, String>> result = sliceQuery.execute();
            for (HColumn<String, String> entry : result.get().getColumns()) {
                T value = convertSafely(returnValueClass,
                                        entry.getValue());
                res.add(value);
            }
        } catch (HectorException e) {
            log.error("getAllColumnsValues failed", e);
            return null;
        }
        return res;
    }

    private <T> T convertSafely(Class<T> returnValueClass,
                                String entryValue) {
        T value = null;
        try {
            Constructor constructor = returnValueClass.getConstructor(
                    new Class[]{String.class});
            value = returnValueClass.cast(constructor.newInstance(entryValue));
        } catch (Exception e) {
            log.error("Error in casting the value from String to {}",
                      new Object[]{returnValueClass.toString(), e});
        }
        return value;
    }

    public int getExpirationSeconds() {
        return expirationSecs;
    }

}
