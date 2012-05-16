/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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


/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/26/12
 */
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
                                                 String endRange, Class<T> returnValueClass,
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
                Object value = invokeDecode(returnValueClass, entry.getValue());
                try{
                res.put(entry.getName(), returnValueClass.cast(value));
                }
                catch (ClassCastException e){
                    log.error("value {} cannot be casted to {}", new Object[]{value.toString(), returnValueClass.toString(), e});
                }
            }
        } catch (HectorException e) {
            log.error("slice query failed", e);
        }
        return res;
    }

    public <T> Map<String, T> executeSliceQuery(List<String> keys,
                                                 String startRange,
                                                 String endRange, Class<T> returnValueClass,
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
                    Object value = invokeDecode(returnValueClass, entry.getValue());
                    try{
                    res.put(entry.getName(), returnValueClass.cast(value));
                    }
                    catch (ClassCastException e){
                        log.error("value {} cannot be casted to {}", new Object[]{value.toString(), returnValueClass.toString(), e});
                    }
                }
            }
        } catch (HectorException e) {
            log.error("multiget slice query failed", e);
        }
        return res;
    }

    public <T> List<T> getAllColumnsValues(String key, Class<T> returnValueClass, int maxResultItems) {
        List<T> res = new ArrayList<T>();
        try {
            SliceQuery<String, String, String> sliceQuery = HFactory.createSliceQuery(
                    keyspace, ss, ss, ss);
            sliceQuery.setColumnFamily(columnFamily);
            sliceQuery.setKey(key);
            sliceQuery.setRange(null, null, false, maxResultItems);

            QueryResult<ColumnSlice<String, String>> result = sliceQuery.execute();
            for (HColumn<String, String> entry : result.get().getColumns()) {
                Object value = invokeDecode(returnValueClass, entry.getValue());
                try{
                res.add(returnValueClass.cast(value));
                }
                catch (ClassCastException e){
                    log.error("value {} cannot be casted to {}", new Object[]{value.toString(), returnValueClass.toString(), e});
                }
            }
        } catch (HectorException e) {
            log.error("getAllColumnsValues failed", e);
            return null;
        }
        return res;
    }

    private <T> Object invokeDecode(Class<T> returnValueClass, String entryValue){
        Object value = null;
        try {
            Method decode = returnValueClass.getMethod("decode",
                                                       String.class);
            value = decode.invoke(null, entryValue);
        } catch (Exception e) {
            log.error("Error in decoding the value", e);
        }
        return value;
    }

    public int getExpirationSeconds() {
        return expirationSecs;
    }

}
