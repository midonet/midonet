package com.midokura.midolman.mgmt.rest_api;

import java.util.HashMap;
import java.util.Map;

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
import com.midokura.midolman.mgmt.data.dao.zookeeper.DtoMetricQuery;
import com.midokura.midolman.mgmt.data.dao.zookeeper.DtoMetricQueryResponse;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.servlet.ServletSupport;
import com.midokura.midolman.monitoring.store.CassandraStore;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 5/4/12
 */
public class TestMonitoringQuery extends JerseyTest {

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";
    private static CassandraStore store;
    String interfaceName = "myInterface";
    String granularity = "1m";
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

    public TestMonitoringQuery() throws TestContainerException {
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
    public void testQueryOneDay() {
        // Insert data into Cassandra
        for (int i = 0; i < numEntries; i++) {
            store.addTSPoint(interfaceName, epochNow + i, Integer.toString(i),
                             metricName,
                             granularity);
        }

        // Create the DtoMetricQuery object
        DtoMetricQuery query = new DtoMetricQuery();
        query.setInterfaceName(interfaceName);
        query.setMetricName(metricName);
        query.setStartEpochTime(epochNow);
        query.setEndEpochTime(epochNow + numEntries);
        query.setGranularity(granularity);

        DtoMetricQueryResponse response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_JSON)
                        .post(DtoMetricQueryResponse.class, query);

        assertThat("The number of results is correct",
                   response.getResults().size(), equalTo(numEntries));
        for (int i = 0; i < numEntries; i++) {
            assertThat("The values are right", Integer.toString(i),
                       equalTo(response.getResults()
                                       .get(Long.toString(epochNow + i))));
        }

    }

    @Test
    public void testQueryBorders() {

        int numberOfSamples = 1;
        // Insert data into Cassandra
        for (int i = 0; i < numEntries; i++) {
            store.addTSPoint(interfaceName, epochNow + i, Integer.toString(i),
                             metricName,
                             granularity);
        }

        // Create the DtoMetricQuery object, that queries from epochNow to epochNow+numberOfSamples
        DtoMetricQuery query = new DtoMetricQuery();
        query.setInterfaceName(interfaceName);
        query.setMetricName(metricName);
        query.setStartEpochTime(epochNow);
        query.setEndEpochTime(epochNow + numberOfSamples);
        query.setGranularity(granularity);

        DtoMetricQueryResponse response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_JSON)
                        .post(DtoMetricQueryResponse.class, query);

        assertThat("The number of results is correct",
                   response.getResults().size(), equalTo(numberOfSamples + 1));
        for (int i = 0; i < numberOfSamples; i++) {
            assertThat("The values are right", Integer.toString(i),
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
        for (int i = numEntries - numberOfSamples; i < numEntries; i++) {
            assertThat("The values are right", Integer.toString(i),
                       equalTo(response.getResults()
                                       .get(Long.toString(epochNow + i))));
        }
    }


    @Test
    public void emptyQuery() {
        // Insert data into Cassandra
        for (int i = 0; i < numEntries; i++) {
            store.addTSPoint(interfaceName, epochNow + i, Integer.toString(i),
                             metricName,
                             granularity);
        }

        // Create the DtoMetricQuery object
        DtoMetricQuery query = new DtoMetricQuery();
        query.setInterfaceName(interfaceName);
        query.setMetricName(metricName);
        query.setStartEpochTime(epochNow + numEntries);
        query.setEndEpochTime(epochNow + numEntries + numEntries);
        query.setGranularity(granularity);

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
        for (int i = 0; i < numEntries; i++) {
            store.addTSPoint(interfaceName, epochNow + i, Integer.toString(i),
                             metricName,
                             granularity);
        }
        long epochTomorrow = epochNow + (24 * 60 * 60 * 1000);
        // Create the DtoMetricQuery object
        DtoMetricQuery query = new DtoMetricQuery();
        query.setInterfaceName(interfaceName);
        query.setMetricName(metricName);
        query.setStartEpochTime(epochNow);
        query.setEndEpochTime(epochNow + epochTomorrow);
        query.setGranularity(granularity);

        DtoMetricQueryResponse response =
                resource().path("/metrics/query").type(
                        VendorMediaType.APPLICATION_MONITORING_QUERY_JSON)
                        .post(DtoMetricQueryResponse.class, query);

        assertThat("The number of results is correct",
                   response.getResults().size(), equalTo(numEntries));
        for (int i = 0; i < numEntries; i++) {
            assertThat("The values are right", Integer.toString(i),
                       equalTo(response.getResults()
                                       .get(Long.toString(epochNow + i))));
        }

    }

}
