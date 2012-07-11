/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.config;

import javax.servlet.ServletContext;

import junit.framework.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import static junit.framework.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;

import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import static com.midokura.midolman.mgmt.config.AppConfig.MONITORING_CASSANDRA_COLUMN_FAMILY;
import static com.midokura.midolman.mgmt.config.AppConfig.MONITORING_CASSANDRA_KEYSPACE;
import static com.midokura.midolman.mgmt.config.AppConfig.MONITORING_CASSANDRA_REPLICATION_FACTOR;

@RunWith(MockitoJUnitRunner.class)
public class TestAppConfig {

    private AppConfig testObject;
    private MonitoringConfiguration monConfig;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ServletContext context;

    @Before
    public void setUp() throws Exception {
        testObject = new AppConfig(context);
        monConfig =
            testObject.getConfigProvider()
                      .getConfig(MonitoringConfiguration.class);
    }

    @Test
    public void testGetVersionExists() throws Exception {
        doReturn("v1").when(context).getInitParameter(AppConfig.versionKey);

        String result = testObject.getVersion();

        Assert.assertEquals("v1", result);
    }

    @Test(expected = InvalidConfigException.class)
    public void testGetVersionNotExists() throws Exception {
        doReturn(null).when(context).getInitParameter(AppConfig.versionKey);
        testObject.getVersion();
    }

    @Test
    public void testGetDataStoreClassNameExists() throws Exception {
        doReturn("foo").when(context).getInitParameter(
            AppConfig.dataStoreKey);

        String result = testObject.getDataStoreClassName();

        Assert.assertEquals("foo", result);
    }

    @Test
    public void testGetDataStoreClassNameNotExists() throws Exception {
        doReturn(null).when(context).getInitParameter(
            AppConfig.dataStoreKey);

        String result = testObject.getDataStoreClassName();

        Assert.assertEquals(AppConfig.defaultDatatStore, result);
    }

    @Test
    public void testGetAuthorizerClassNameExists() throws Exception {
        doReturn("foo").when(context).getInitParameter(
            AppConfig.authorizerKey);

        String result = testObject.getAuthorizerClassName();

        Assert.assertEquals("foo", result);
    }

    @Test
    public void testGetAuthorizerClassNameNotExists() throws Exception {
        doReturn(null).when(context).getInitParameter(
            AppConfig.authorizerKey);

        String result = testObject.getAuthorizerClassName();

        Assert.assertEquals(AppConfig.defaultAuthorizer, result);
    }

    @Test
    public void testGetZkConnectionStringExists() throws Exception {
        doReturn("foo").when(context).getInitParameter(
            AppConfig.zkConnStringKey);

        String result = testObject.getZkConnectionString();

        Assert.assertEquals("foo", result);
    }

    @Test
    public void testGetZkConnectionStringNotExists() throws Exception {
        doReturn(null).when(context).getInitParameter(
            AppConfig.zkConnStringKey);

        String result = testObject.getZkConnectionString();

        Assert.assertEquals(AppConfig.defaultZkConnString, result);
    }

    @Test
    public void testGetZkTimeoutExists() throws Exception {
        doReturn("1000").when(context).getInitParameter(
            AppConfig.zkTimeoutKey);

        int result = testObject.getZkTimeout();

        Assert.assertEquals(1000, result);
    }

    @Test
    public void testGetZkTimeoutNotExists() throws Exception {
        doReturn(null).when(context).getInitParameter(
            AppConfig.zkTimeoutKey);

        int result = testObject.getZkTimeout();

        Assert.assertEquals(AppConfig.defaultZkTimeout, result);
    }

    @Test(expected = InvalidConfigException.class)
    public void testGetZkTimeoutBadValue() throws Exception {
        doReturn("foo").when(context).getInitParameter(
            AppConfig.zkTimeoutKey);
        testObject.getZkTimeout();
    }

    @Test
    public void testGetZkRootPathExists() throws Exception {
        doReturn("foo").when(context).getInitParameter(AppConfig.zkRootKey);

        String result = testObject.getZkRootPath();

        Assert.assertEquals("foo", result);
    }

    @Test
    public void testGetZkRootPathNotExists() throws Exception {
        doReturn(null).when(context).getInitParameter(AppConfig.zkRootKey);

        String result = testObject.getZkRootPath();

        Assert.assertEquals(AppConfig.defaultZkRootPath, result);
    }

    @Test
    public void testGetCassandraServers() throws Exception {
        doReturn(null)
            .when(context)
            .getInitParameter(AppConfig.CASSANDRA_SERVERS);
        assertEquals("127.0.0.1:9160", monConfig.getCassandraServers());

        doReturn("value")
            .when(context)
            .getInitParameter(AppConfig.CASSANDRA_SERVERS);
        assertEquals("value", monConfig.getCassandraServers());
    }

    @Test
    public void testGetCassandraClusterServers() throws Exception {
        doReturn(null)
            .when(context)
            .getInitParameter(AppConfig.CASSANDRA_CLUSTER);
        assertEquals("midonet", monConfig.getCassandraCluster());

        doReturn("value")
            .when(context)
            .getInitParameter(AppConfig.CASSANDRA_CLUSTER);
        assertEquals("value", monConfig.getCassandraCluster());
    }

    @Test
    public void testGetMonitoringCassandraKeyspace() throws Exception {
        doReturn(null)
            .when(context)
            .getInitParameter(MONITORING_CASSANDRA_KEYSPACE);
        assertEquals("midonet_monitoring_keyspace",
                     monConfig.getMonitoringCassandraKeyspace());

        doReturn("value")
            .when(context)
            .getInitParameter(MONITORING_CASSANDRA_KEYSPACE);
        assertEquals("value", monConfig.getMonitoringCassandraKeyspace());
    }

    @Test
    public void testGetMonitoringCassandraColumnFamily() throws Exception {
        doReturn(null)
            .when(context)
            .getInitParameter(MONITORING_CASSANDRA_COLUMN_FAMILY);
        assertEquals("midonet_monitoring_column_family",
                     monConfig.getMonitoringCassandraColumnFamily());

        doReturn("value")
            .when(context)
            .getInitParameter(MONITORING_CASSANDRA_COLUMN_FAMILY);
        assertEquals("value", monConfig.getMonitoringCassandraColumnFamily());
    }

    @Test
    public void testGetMonitoringCassandraReplicationFactor() throws Exception {
        doReturn(null)
            .when(context)
            .getInitParameter(MONITORING_CASSANDRA_REPLICATION_FACTOR);

        assertEquals(2, monConfig.getMonitoringCassandraReplicationFactor());

        doReturn("" + 3001)
            .when(context)
            .getInitParameter(MONITORING_CASSANDRA_REPLICATION_FACTOR);

        assertEquals(3001, monConfig.getMonitoringCassandraReplicationFactor());
    }

    @Test
    public void testGetMonitoringCassandraExpirationTimeout() throws Exception {
        doReturn(null)
            .when(context)
            .getInitParameter(
                AppConfig.MONITORING_CASSANDRA_EXPIRATION_TIMEOUT);
        assertEquals(744600, monConfig.getMonitoringCassandraExpirationTimeout());

        doReturn("" + 3001)
            .when(context)
            .getInitParameter(
                AppConfig.MONITORING_CASSANDRA_EXPIRATION_TIMEOUT);
        assertEquals(3001, monConfig.getMonitoringCassandraExpirationTimeout());
    }
}
