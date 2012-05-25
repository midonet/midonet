/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.rest_api;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.spi.container.TestContainerException;
import org.apache.zookeeper.KeeperException;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.midokura.midolman.mgmt.auth.NoAuthClient;
import com.midokura.midolman.mgmt.data.StaticMockDaoFactory;
import com.midokura.midolman.mgmt.data.dto.client.DtoMetric;
import com.midokura.midolman.mgmt.data.dto.client.DtoMetricQuery;
import com.midokura.midolman.mgmt.data.dto.client.DtoMetricQueryResponse;
import com.midokura.midolman.mgmt.data.dto.client.DtoMetricTarget;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.servlet.ServletSupport;
import com.midokura.midolman.monitoring.store.CassandraStore;

/**
 * Date: 5/4/12
 */
public class TestMonitoring extends JerseyTest {

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";
    private static CassandraStore store;
    String targetIdentifier = "826400c0-a589-11e1-b3dd-0800200c9a66";
    String type = "Test";
    String metricName = "TestMetric";
    long epochNow = System.currentTimeMillis();
    static int replicationFactor = 1;
    static int ttlInSecs = 1000;
    int numEntries = 10;


    static final Map<String, String> authFilterInitParams = new HashMap<String, String>();

    static {
        authFilterInitParams.put(ServletSupport.AUTH_CLIENT_CONFIG_KEY,
                                 NoAuthClient.class.getName());
    }

    public TestMonitoring() throws TestContainerException {
        super(FuncTest.appDesc);

    }

    @BeforeClass
    public static void setUpCassandra() throws Exception {
        EmbeddedCassandraServerHelper.startEmbeddedCassandra();
    }

    @Before
    public void before() throws KeeperException {
        resource().type(VendorMediaType.APPLICATION_JSON)
                .get(ClientResponse.class);

        // Needs to be set up everytime because the keyspace and columnfamily gets erased
        // by EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
        store = new CassandraStore("localhost:9171",
                                   "Mido Cluster",
                                   "MM_Monitoring",
                                   "Monitoring",
                                   replicationFactor, ttlInSecs);

    }

    @After
    public void resetDirectory() throws Exception {
        StaticMockDaoFactory.clearFactoryInstance();
        EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();

    }

    @Test
    public void testGetMetric() {
        // No metric in the store
        DtoMetricTarget target = new DtoMetricTarget();
        target.setTargetUUID(UUID.fromString(targetIdentifier));
        target.setType(type);

        DtoMetric[] metrics = resource().path("/metrics/filter").type(
                VendorMediaType.APPLICATION_METRIC_TARGET_JSON)
                .post(DtoMetric[].class, target);
        assertThat("The size of the array is correct, it's empty",
                   metrics.length, equalTo(0));

        store.addMetric(type, targetIdentifier, metricName);

        metrics = resource().path("/metrics/filter").type(
                VendorMediaType.APPLICATION_METRIC_TARGET_JSON)
                .post(DtoMetric[].class, target);
        assertThat("The size of the array is correct", metrics.length,
                   equalTo(1));
        assertThat("We retrieved the metric correctly", metrics[0].getName(),
                   equalTo(metricName));
        assertThat("The metric was assigned to the right targetObject",
                   metrics[0].getTargetIdentifier().toString(),
                   equalTo(targetIdentifier));

        // add a metric
        String secondMetricName = "TestMetric2";
        store.addMetric(type, targetIdentifier, secondMetricName);
        metrics = resource().path("/metrics/filter").type(
                VendorMediaType.APPLICATION_METRIC_TARGET_JSON)
                .post(DtoMetric[].class, target);
        assertThat("The size of the array is correct", metrics.length,
                   equalTo(2));
        assertThat("We retrieved the metric correctly", metrics[0].getName(),
                   equalTo(metricName));
        assertThat("We retrieved the metric correctly", metrics[1].getName(),
                   equalTo(secondMetricName));

        assertThat("The metric was assigned to the right targetObject",
                   metrics[0].getTargetIdentifier().toString(),
                   equalTo(targetIdentifier));
        assertThat("The metric was assigned to the right targetObject",
                   metrics[1].getTargetIdentifier().toString(),
                   equalTo(targetIdentifier));

    }

    @Test
    public void testQueryOneDay() {
        // Insert data into Cassandra
        for (long i = 0; i < numEntries; i++) {
            store.addTSPoint(type, targetIdentifier, metricName, epochNow + i, i

            );
        }

        // Create the DtoMetricQuery object
        DtoMetricQuery query = new DtoMetricQuery();
        query.setType(type);
        query.setInterfaceName(targetIdentifier);
        query.setMetricName(metricName);
        query.setStartEpochTime(epochNow);
        query.setEndEpochTime(epochNow + numEntries);

        DtoMetricQueryResponse response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_JSON)
                        .post(DtoMetricQueryResponse.class, query);

        assertThat("The number of results is correct",
                   response.getResults().size(), equalTo(numEntries));
        for (long i = 0; i < numEntries; i++) {
            assertThat("The values are right", i,
                       equalTo(response.getResults()
                                       .get(Long.toString(epochNow + i))));
        }

    }

    @Test
    public void testQueryBorders() {

        int numberOfSamples = 1;
        // Insert data into Cassandra
        for (long i = 0; i < numEntries; i++) {
            store.addTSPoint(type, targetIdentifier, metricName, epochNow + i, i

            );
        }

        // Create the DtoMetricQuery object, that queries from epochNow to epochNow+numberOfSamples
        DtoMetricQuery query = new DtoMetricQuery();
        query.setInterfaceName(targetIdentifier);
        query.setType(type);
        query.setMetricName(metricName);
        query.setStartEpochTime(epochNow);
        query.setEndEpochTime(epochNow + numberOfSamples);

        DtoMetricQueryResponse response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_JSON)
                        .post(DtoMetricQueryResponse.class, query);

        assertThat("The number of results is correct",
                   response.getResults().size(), equalTo(numberOfSamples + 1));
        for (long i = 0; i < numberOfSamples; i++) {
            assertThat("The values are right", i,
                       equalTo(response.getResults()
                                       .get(Long.toString(epochNow + i))));
        }

        // let's query the end side
        query.setStartEpochTime(epochNow + numEntries - numberOfSamples - 1);
        query.setEndEpochTime(epochNow + numEntries);

        response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_JSON)
                        .post(DtoMetricQueryResponse.class, query);
        assertThat("The number of results is correct",
                   response.getResults().size(), equalTo(numberOfSamples + 1));
        for (long i = numEntries - numberOfSamples; i < numEntries; i++) {
            assertThat("The values are right", i,
                       equalTo(response.getResults()
                                       .get(Long.toString(epochNow + i))));
        }
    }


    @Test
    public void emptyQuery() {
        // Insert data into Cassandra
        for (long i = 0; i < numEntries; i++) {
            store.addTSPoint(type, targetIdentifier, metricName, epochNow + i, i

            );
        }

        // Create the DtoMetricQuery object
        DtoMetricQuery query = new DtoMetricQuery();
        query.setInterfaceName(targetIdentifier);
        query.setType(type);
        query.setMetricName(metricName);
        query.setStartEpochTime(epochNow + numEntries);
        query.setEndEpochTime(epochNow + numEntries + numEntries);

        DtoMetricQueryResponse response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_JSON)
                        .post(DtoMetricQueryResponse.class, query);

        assertThat("The number of results is correct",
                   response.getResults().size(), equalTo(0));
    }

    @Test
    public void queryBroaderThenSamples() {
        // Insert data into Cassandra
        for (long i = 0; i < numEntries; i++) {
            store.addTSPoint(type, targetIdentifier, metricName, epochNow + i, i

            );
        }
        long epochTomorrow = epochNow + (24 * 60 * 60 * 1000);

        // Create the DtoMetricQuery object
        DtoMetricQuery query = new DtoMetricQuery();
        query.setInterfaceName(targetIdentifier);
        query.setType(type);
        query.setMetricName(metricName);
        query.setStartEpochTime(epochNow);
        query.setEndEpochTime(epochTomorrow);

        DtoMetricQueryResponse response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_JSON)
                        .post(DtoMetricQueryResponse.class, query);

        assertThat("The number of results is correct",
                   response.getResults().size(), equalTo(numEntries));
        for (long i = 0; i < numEntries; i++) {
            assertThat("The values are right", i,
                       equalTo(response.getResults()
                                       .get(Long.toString(epochNow + i))));
        }

    }

}
