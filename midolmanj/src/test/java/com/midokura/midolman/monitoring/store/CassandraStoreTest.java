/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.midolman.monitoring.store;

import java.util.Map;

import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.midokura.midolman.monitoring.store.CassandraStore;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/23/12
 */
public class CassandraStoreTest {

    String interfaceName = "myInterface";
    String granularity = "1m";
    String metricName = "FakeMetric";
    long epochNow = System.currentTimeMillis();
    long epochDayAfterTomorrow = epochNow + 2 * (24*60*60*1000);
    static int replicationFactor = 1;
    static int ttlInSecs = 1000;
    int numEntries = 10;
    CassandraStore store;



    @BeforeClass
    public static void setUpCassandra() throws Exception {
        EmbeddedCassandraServerHelper.startEmbeddedCassandra();
    }

    @Before
    public void setUpCassandraStore(){
        // Needs to be set up everytime because the keyspace and columnfamily gets erased
        // by EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
        store = new CassandraStore("localhost:9171",
                                   "Mido Cluster",
                                   "MM_Monitoring",
                                   "TestColumnFamily",
                                   replicationFactor, ttlInSecs);
    }

    @After
    public void cleanData(){
        EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
    }

    @Test
    public void testMultiGetSliceQuery() {

        // Insert data into Cassandra for the last 2 days
        for (int i = 0; i < numEntries; i++) {
            store.addTSPoint(interfaceName, epochNow + i, Integer.toString(i),
                             metricName,
                             granularity);
        }

        for (int i = 0; i < numEntries; i++) {
            store.addTSPoint(interfaceName, epochDayAfterTomorrow + i, Integer.toString(i),
                             metricName,
                             granularity);
        }
        Map<String, String > res = store.getTSPoint(interfaceName, epochNow, epochDayAfterTomorrow+numEntries, metricName, granularity);
        assertThat("MultiGetSlice query number of results", res.size(), equalTo(numEntries*2));

        for (int i = 0; i < numEntries; i++) {
            assertThat("MultiGetSlice query went right", Integer.toString(i),
                       equalTo(res.get(Long.toString(epochNow+i))));
        }

        for (int i = 0; i < numEntries; i++) {
            assertThat("MultiGetSlice query went right", Integer.toString(i),
                       equalTo(res.get(Long.toString(epochDayAfterTomorrow+i))));
        }

    }

    @Test
    public void testSliceQuery() {

        // Insert data into Cassandra
        for (int i = 0; i < numEntries; i++) {
            store.addTSPoint(interfaceName, epochNow + i, Integer.toString(i),
                             metricName,
                             granularity);
        }

        for (int i = 0; i < numEntries; i++) {
            String val = store.getTSPoint(interfaceName, epochNow + i,
                                          metricName,
                                          granularity);
            assertThat("Single point get", Integer.toString(i),
                       equalTo(val));
        }
        Map<String, String > res = store.getTSPoint(interfaceName, epochNow, epochNow+numEntries, metricName, granularity);
        assertThat("Slice query number of results", res.size(), equalTo(numEntries));
        for (int i = 0; i < numEntries; i++) {
            assertThat("Slice query went right", Integer.toString(i),
                       equalTo(res.get(Long.toString(epochNow+i))));
        }

    }

    @AfterClass
    public static void tearDownCassandra() throws Exception {
        EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
    }
}
