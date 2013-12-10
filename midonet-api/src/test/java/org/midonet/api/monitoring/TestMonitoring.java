/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package org.midonet.api.monitoring;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.spi.container.TestContainerException;
import org.apache.zookeeper.KeeperException;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.midonet.cassandra.CassandraClient;
import org.midonet.midolman.monitoring.store.CassandraStore;
import org.midonet.api.VendorMediaType;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.client.dto.DtoMetric;
import org.midonet.client.dto.DtoMetricQuery;
import org.midonet.client.dto.DtoMetricQueryResponse;
import org.midonet.client.dto.DtoMetricTarget;


import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Date: 5/4/12
 */
public class TestMonitoring extends JerseyTest {

    private static CassandraStore store;
    UUID targetIdentifier =
            UUID.fromString("826400c0-a589-11e1-b3dd-0800200c9a66");
    String type = "Test";
    String metricName = "TestMetric";
    long epochNow = System.currentTimeMillis();
    int numEntries = 10;

    public TestMonitoring() throws TestContainerException {
        // Note: this call will start the embedded Cassandra server.
        super(FuncTest.getBuilder().build());

    }

    @Before
    public void before() throws KeeperException {
        resource().type(VendorMediaType.APPLICATION_JSON)
                .get(ClientResponse.class);

        // Needs to be set up everytime because the keyspace and columnfamily
        // gets erased by EmbeddedCassandraServerHelper.cleanEmbeddedCassandra()
        CassandraClient client = new CassandraClient(
                FuncTest.cassandraServers,
                FuncTest.cassandraMaxActiveConns,
                FuncTest.cassandraCluster,
                FuncTest.monitoringCassandraKeyspace,
                FuncTest.monitoringCassandraColumnFamily,
                FuncTest.replicationFactor, FuncTest.ttlInSecs,
                2500, false, 10, 5000, null
        );
        store = new CassandraStore(client);
        store.initialize();
    }

    @After
    public void resetDirectory() throws Exception {
        StaticMockDirectory.clearDirectoryInstance();
        EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
    }

    @Test
    public void testGetMetric() {
        // No metric in the store
        DtoMetricTarget target = new DtoMetricTarget();

        target.setTargetIdentifier(targetIdentifier);

        DtoMetric[] metrics = resource().path("/metrics/filter").type(
                VendorMediaType.APPLICATION_METRIC_TARGET_JSON)
                .post(DtoMetric[].class, target);
        assertThat("The size of the array is correct, it's empty",
                metrics.length, equalTo(0));

        // add type to target
        store.addMetricTypeToTarget(targetIdentifier.toString(), type);
        store.addMetricToType(type, metricName);

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
                                "targetIdentifier", is(targetIdentifier)),
                        Matchers.<DtoMetric>hasProperty(
                                "type", is(type))));

        // add a metric
        String secondMetricName = "TestMetric2";
        store.addMetricToType(type, secondMetricName);
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

        assertThat("The metric has the right type",
                metrics[0].getType(),
                equalTo(type));

        assertThat("The metric has the right type",
                metrics[1].getType(),
                equalTo(type));

    }

    @Test
    public void testMultipleQueries() {

        DtoMetricTarget target = new DtoMetricTarget();
        target.setTargetIdentifier(targetIdentifier);

        store.addMetricTypeToTarget(targetIdentifier.toString(), type);
        store.addMetricToType(type, metricName);

        DtoMetric[] metrics = resource().path("/metrics/filter")
                .type(VendorMediaType.APPLICATION_METRIC_TARGET_JSON)
                .post(DtoMetric[].class, target);

        // add second metric
        String secondMetricName = "TestMetric2";
        store.addMetricToType(type,
                secondMetricName);

        // Insert data into Cassandra for both metrics
        for (long i = 0; i < numEntries; i++) {
            store.addTSPoint(type, targetIdentifier.toString(), metricName,
                    epochNow + i, i

            );
        }

        // Insert data into Cassandra
        for (long i = 0; i < numEntries; i++) {
            store.addTSPoint(type, targetIdentifier.toString(), secondMetricName,
                    epochNow + i, i

            );
        }

        // Create the DtoMetricQuery object
        List<DtoMetricQuery> queries = new ArrayList<DtoMetricQuery>();
        DtoMetricQuery query = new DtoMetricQuery();
        query.setType(type);
        query.setTargetIdentifier(targetIdentifier);
        query.setMetricName(metricName);
        query.setTimeStampStart(epochNow);
        query.setTimeStampEnd(epochNow + numEntries);

        queries.add(query);
        query.setMetricName(secondMetricName);
        queries.add(query);

        DtoMetricQueryResponse[] response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_COLLECTION_JSON)
                        .post(DtoMetricQueryResponse[].class, queries);
        assertThat("We got the response for both queries", response.length, equalTo(2));
        for (int j = 0; j < 2; j++) {
            assertThat("The number of results is correct for the queries",
                    response[j].getResults().size(), equalTo(numEntries));
            for (long i = 0; i < numEntries; i++) {
                assertThat("The values are right for the queries", i,
                        equalTo(response[j].getResults()
                                .get(Long.toString(epochNow + i))));
            }
        }
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
        query.setTimeStampStart(epochNow);
        query.setTimeStampEnd(epochNow + numEntries);

        List<DtoMetricQuery> queries = new ArrayList<DtoMetricQuery>();
        queries.add(query);

        DtoMetricQueryResponse[] response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_COLLECTION_JSON)
                        .post(DtoMetricQueryResponse[].class, queries);

        assertThat("The array of MetricQueryResponse has the right size",
                   response.length, equalTo(1));

        assertThat("The number of results is correct",
                response[0].getResults().size(), equalTo(numEntries));
        for (long i = 0; i < numEntries; i++) {
            assertThat("The values are right", i,
                    equalTo(response[0].getResults()
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
        query.setTimeStampStart(epochNow);
        query.setTimeStampEnd(epochNow + numberOfSamples);
        List<DtoMetricQuery> queries = new ArrayList<DtoMetricQuery>();
        queries.add(query);

        DtoMetricQueryResponse[] response =
                resource().path("/metrics/query").type(
                    VendorMediaType.APPLICATION_MONITORING_QUERY_COLLECTION_JSON)
                        .post(DtoMetricQueryResponse[].class, queries);

        assertThat("The array of MetricQueryResponse has the right size",
                   response.length, equalTo(1));


        assertThat("The number of results is correct",
                response[0].getResults().size(), equalTo(numberOfSamples + 1));

        for (long i = 0; i < numberOfSamples; i++) {
            assertThat("The values are right", i,
                    equalTo(response[0].getResults()
                                       .get(Long.toString(epochNow + i))));
        }

        // let's query the end side
        query.setTimeStampStart(epochNow + numEntries - numberOfSamples - 1);
        query.setTimeStampEnd(epochNow + numEntries);
        queries = new ArrayList<DtoMetricQuery>();
        queries.add(query);

        response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_COLLECTION_JSON)
                        .post(DtoMetricQueryResponse[].class, queries);

        assertThat("The array of MetricQueryResponse has the right size",
                   response.length, equalTo(1));

        assertThat("The number of results is correct",
                response[0].getResults().size(), equalTo(numberOfSamples + 1));

        for (long i = numEntries - numberOfSamples; i < numEntries; i++) {
            assertThat("The values are right", i,
                    equalTo(response[0].getResults()
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
        query.setTimeStampStart(epochNow + numEntries);
        query.setTimeStampEnd(epochNow + numEntries + numEntries);

        List<DtoMetricQuery> queries = new ArrayList<DtoMetricQuery>();
        queries.add(query);

        DtoMetricQueryResponse[] response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_COLLECTION_JSON)
                        .post(DtoMetricQueryResponse[].class, queries);

        assertThat("The array of MetricQueryResponse has the right size",
                   response.length, equalTo(1));

        assertThat("The number of results is correct",
                   response[0].getResults().size(), equalTo(0));

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
        query.setTimeStampStart(epochNow);
        query.setTimeStampEnd(epochTomorrow);

        List<DtoMetricQuery> queries = new ArrayList<DtoMetricQuery>();
        queries.add(query);

        DtoMetricQueryResponse[] response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_COLLECTION_JSON)
                        .post(DtoMetricQueryResponse[].class, queries);


        assertThat("The array of MetricQueryResponse has the right size",
                   response.length, equalTo(1));

        assertThat("The number of results is correct",
                response[0].getResults().size(), equalTo(numEntries));
        for (long i = 0; i < numEntries; i++) {
            assertThat("The values are right", i,
                    equalTo(response[0].getResults()
                            .get(Long.toString(epochNow + i))));
        }

    }

}
