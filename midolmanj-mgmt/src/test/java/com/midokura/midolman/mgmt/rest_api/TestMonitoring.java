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
import org.hamcrest.Matchers;
import org.json.simple.JSONValue;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import com.midokura.midolman.mgmt.auth.NoAuthClient;
import com.midokura.midolman.mgmt.data.StaticMockDaoFactory;
import com.midokura.midolman.mgmt.data.dto.client.DtoMetric;
import com.midokura.midolman.mgmt.data.dto.client.DtoMetricQuery;
import com.midokura.midolman.mgmt.data.dto.client.DtoMetricQueryResponse;
import com.midokura.midolman.mgmt.data.dto.client.DtoMetricTarget;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.servlet.ServletSupport;
import com.midokura.midolman.monitoring.store.CassandraStore;
import static com.midokura.midolman.mgmt.config.AppConfig.CASSANDRA_CLUSTER;
import static com.midokura.midolman.mgmt.config.AppConfig.CASSANDRA_SERVERS;
import static com.midokura.midolman.mgmt.config.AppConfig.MONITORING_CASSANDRA_COLUMN_FAMILY;
import static com.midokura.midolman.mgmt.config.AppConfig.MONITORING_CASSANDRA_EXPIRATION_TIMEOUT;
import static com.midokura.midolman.mgmt.config.AppConfig.MONITORING_CASSANDRA_KEYSPACE;
import static com.midokura.midolman.mgmt.config.AppConfig.MONITORING_CASSANDRA_REPLICATION_FACTOR;

/**
 * Date: 5/4/12
 */
public class TestMonitoring extends JerseyTest {

    private static CassandraStore store;
    public static final String cassandraCluster = "midonet";
    public static final String monitoringCassandraKeyspace = "midonet_monitoring_keyspace";
    public static final String monitoringCassandraColumnFamily = "midonet_monitoring_column_family";
    UUID targetIdentifier =
        UUID.fromString("826400c0-a589-11e1-b3dd-0800200c9a66");
    String type = "Test";
    String metricName = "TestMetric";
    long epochNow = System.currentTimeMillis();
    static int replicationFactor = 1;
    static int ttlInSecs = 1000;
    int numEntries = 10;

    final static String cassandraServers = "127.0.0.1:9171";


    static final Map<String, String> authFilterInitParams = new HashMap<String, String>();

    static {
        authFilterInitParams.put(ServletSupport.AUTH_CLIENT_CONFIG_KEY,
                                 NoAuthClient.class.getName());
    }

    public TestMonitoring() throws TestContainerException {
        super(
            FuncTest
                .getBuilder()
                .contextParam(CASSANDRA_SERVERS, cassandraServers)
                .contextParam(CASSANDRA_CLUSTER, cassandraCluster)
                .contextParam(MONITORING_CASSANDRA_KEYSPACE,
                              monitoringCassandraKeyspace)
                .contextParam(MONITORING_CASSANDRA_COLUMN_FAMILY,
                              monitoringCassandraColumnFamily)
                .contextParam(MONITORING_CASSANDRA_REPLICATION_FACTOR,
                              "" + replicationFactor)
                .contextParam(MONITORING_CASSANDRA_EXPIRATION_TIMEOUT,
                              "" + ttlInSecs)
                .build());
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
        store = new CassandraStore(cassandraServers,
                                   cassandraCluster,
                                   monitoringCassandraKeyspace,
                                   monitoringCassandraColumnFamily,
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

        target.setTargetIdentifier(targetIdentifier);
        target.setType(type);

        DtoMetric[] metrics = resource().path("/metrics/filter").type(
            VendorMediaType.APPLICATION_METRIC_TARGET_JSON)
            .post(DtoMetric[].class, target);
        assertThat("The size of the array is correct, it's empty",
                   metrics.length, equalTo(0));

        store.addMetric(type, targetIdentifier.toString(), metricName);

        metrics = resource().path("/metrics/filter")
            .type(VendorMediaType.APPLICATION_METRIC_TARGET_JSON)
            .post(DtoMetric[].class, target);

        assertThat("We have only one element returned",
                   metrics, arrayWithSize(1));

        assertThat("We received the proper element in return",
                   metrics[0],
                   allOf(
                       Matchers.<DtoMetric>hasProperty(
                           "name", is(metricName)),
                       Matchers.<DtoMetric>hasProperty(
                           "targetIdentifier", is(targetIdentifier))));

        // add a metric
        String secondMetricName = "TestMetric2";
        store.addMetric(type, targetIdentifier.toString(), secondMetricName);
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
                   metrics[0].getTargetIdentifier(),
                   equalTo(targetIdentifier));
        assertThat("The metric was assigned to the right targetObject",
                   metrics[1].getTargetIdentifier(),
                   equalTo(targetIdentifier));

    }

    @Test
    public void testQueryOneDay() {
        // Insert data into Cassandra
        for (long i = 0; i < numEntries; i++) {
            store.addTSPoint(type, targetIdentifier.toString(), metricName,
                             epochNow + i, i

            );
        }

        // Create the DtoMetricQuery object
        DtoMetricQuery query = new DtoMetricQuery();
        query.setType(type);
        query.setTargetIdentifier(targetIdentifier);
        query.setMetricName(metricName);
        query.setStartEpochTime(epochNow);
        query.setEndEpochTime(epochNow + numEntries);

        DtoMetricQueryResponse response =
            resource().path("/metrics/query").type(
                VendorMediaType.APPLICATION_MONITORING_QUERY_JSON)
                .post(DtoMetricQueryResponse.class, query);
        String json = JSONValue.toJSONString(response);
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
            store.addTSPoint(type, targetIdentifier.toString(), metricName,
                             epochNow + i, i

            );
        }

        // Create the DtoMetricQuery object, that queries from epochNow to epochNow+numberOfSamples
        DtoMetricQuery query = new DtoMetricQuery();
        query.setTargetIdentifier(targetIdentifier);
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
            store.addTSPoint(type, targetIdentifier.toString(), metricName,
                             epochNow + i, i

            );
        }

        // Create the DtoMetricQuery object
        DtoMetricQuery query = new DtoMetricQuery();
        query.setTargetIdentifier(targetIdentifier);
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
            store.addTSPoint(type, targetIdentifier.toString(), metricName,
                             epochNow + i, i

            );
        }
        long epochTomorrow = epochNow + (24 * 60 * 60 * 1000);

        // Create the DtoMetricQuery object
        DtoMetricQuery query = new DtoMetricQuery();
        query.setTargetIdentifier(targetIdentifier);
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
