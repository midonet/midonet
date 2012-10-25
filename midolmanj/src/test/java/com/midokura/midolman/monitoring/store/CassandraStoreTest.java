/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.midolman.monitoring.store;

import com.midokura.cassandra.CassandraClient;
import com.midokura.util.Waiters;

import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.junit.*;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Date: 4/23/12
 */
public class CassandraStoreTest {

    String interfaceName = "myInterface";
    String type = "Test";
    String metricName = "TestMetric";
    long epochNow = System.currentTimeMillis();
    long epochDayAfterTomorrow = epochNow + 2 * (24 * 60 * 60 * 1000);
    static int replicationFactor = 1;
    static int ttlInSecs = 1000;
    int numEntries = 10;
    CassandraStore store;

    @BeforeClass
    public static void setUpCassandra() throws Exception {
        EmbeddedCassandraServerHelper.startEmbeddedCassandra();
        Waiters.sleepBecause("Cassandra server must come up.", 2);
    }

    @Before
    public void setUpCassandraStore() {
        // Needs to be set up everytime because the keyspace and columnfamily gets erased
        // by EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
        CassandraClient client = new CassandraClient(
                "localhost:9171",
                "Mido Cluster",
                "MM_Monitoring",
                "TestColumnFamily",
                replicationFactor, ttlInSecs);
        store = new CassandraStore(client);
        store.initialize();
    }

    @After
    public void cleanData() {
        EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
    }

    @Test
    public void testMultiGetSliceQuery() {

        // Insert data into Cassandra for the last 2 days
        for (int i = 0; i < numEntries; i++) {
            store.addTSPoint(type, interfaceName, metricName, epochNow + i, i
            );
        }

        for (int i = 0; i < numEntries; i++) {
            store.addTSPoint(type, interfaceName, metricName,
                             epochDayAfterTomorrow + i, i
            );
        }
        Map<String, Long> res = store.getTSPoints(type, interfaceName,
                                                  metricName, epochNow,
                                                  epochDayAfterTomorrow + numEntries
        );
        assertThat("MultiGetSlice query number of results", res.size(),
                   equalTo(numEntries * 2));

        for (long i = 0; i < numEntries; i++) {
            assertThat("MultiGetSlice query went right", i,
                       equalTo(res.get(Long.toString(epochNow + i))));
        }

        for (long i = 0; i < numEntries; i++) {
            assertThat("MultiGetSlice query went right", i,
                       equalTo(res.get(
                               Long.toString(epochDayAfterTomorrow + i))));
        }

    }

    @Test
    public void testSliceQuery() {

        // Insert data into Cassandra
        for (int i = 0; i < numEntries; i++) {
            store.addTSPoint(type, interfaceName, metricName, epochNow + i, i
            );
        }

        for (long i = 0; i < numEntries; i++) {
            long val = store.getTSPoint(type, interfaceName, metricName,
                                        epochNow + i
            );
            assertThat("Single point get", i,
                       equalTo(val));
        }
        Map<String, Long> res = store.getTSPoints(type, interfaceName,
                                                  metricName, epochNow,
                                                  epochNow + numEntries
        );
        assertThat("Slice query number of results", res.size(),
                   equalTo(numEntries));
        for (long i = 0; i < numEntries; i++) {
            assertThat("Slice query went right", i,
                       equalTo(res.get(Long.toString(epochNow + i))));
        }

    }

    @AfterClass
    public static void tearDownCassandra() throws Exception {
        //EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
    }
}
