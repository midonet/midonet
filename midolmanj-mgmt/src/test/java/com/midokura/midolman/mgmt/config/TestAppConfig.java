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

@RunWith(MockitoJUnitRunner.class)
public class TestAppConfig {

	private AppConfig testObject;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private ServletContext context;

	@Before
	public void setUp() throws Exception {
		testObject = new AppConfig(context);
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
	public void testGetZkMgmtRootPathExists() throws Exception {
		doReturn("foo").when(context).getInitParameter(
				AppConfig.zkMgmtRootKey);

		String result = testObject.getZkMgmtRootPath();

		Assert.assertEquals("foo", result);
	}

	@Test
	public void testGetZkMgmtRootPathNotExists() throws Exception {
		doReturn(null).when(context).getInitParameter(
				AppConfig.zkMgmtRootKey);

		String result = testObject.getZkMgmtRootPath();

		Assert.assertEquals(AppConfig.defaultZkMgmtRootPath, result);
	}

    @Test
    public void testGetCassandraServers() throws Exception {
        doReturn(null).when(context).getInitParameter(AppConfig.CASSANDRA_SERVERS);
        assertEquals("default", testObject.getCassandraServers("default"));

        doReturn("value").when(context).getInitParameter(AppConfig.CASSANDRA_SERVERS);
        assertEquals("value", testObject.getCassandraServers("default"));
    }

    @Test
    public void testGetCassandraClusterServers() throws Exception {
        doReturn(null).when(context).getInitParameter(AppConfig.CASSANDRA_CLUSTER);
        assertEquals("default", testObject.getCassandraCluster("default"));

        doReturn("value").when(context).getInitParameter(AppConfig.CASSANDRA_CLUSTER);
        assertEquals("value", testObject.getCassandraCluster("default"));
    }

    @Test
    public void testGetMonitoringCassandraKeyspace() throws Exception {
        doReturn(null).when(context).getInitParameter(AppConfig.MONITORING_CASSANDRA_KEYSPACE);
        assertEquals("default", testObject.getMonitoringCassandraKeyspace("default"));

        doReturn("value").when(context).getInitParameter(AppConfig.MONITORING_CASSANDRA_KEYSPACE);
        assertEquals("value", testObject.getMonitoringCassandraKeyspace("default"));
    }

    @Test
    public void testGetMonitoringCassandraColumnFamily() throws Exception {
        doReturn(null).when(context).getInitParameter(AppConfig.MONITORING_CASSANDRA_COLUMN_FAMILY);
        assertEquals("default", testObject.getMonitoringCassandraColumnFamily("default"));

        doReturn("value").when(context).getInitParameter(AppConfig.MONITORING_CASSANDRA_COLUMN_FAMILY);
        assertEquals("value", testObject.getMonitoringCassandraColumnFamily("default"));
    }

    @Test
    public void testGetMonitoringCassandraReplicationFactor() throws Exception {
        doReturn(null).when(context).getInitParameter(AppConfig.MONITORING_CASSANDRA_REPLICATION_FACTOR);
        assertEquals(8069, testObject.getMonitoringCassandraReplicationFactor(8069));

        doReturn("" + 3001).when(context).getInitParameter(AppConfig.MONITORING_CASSANDRA_REPLICATION_FACTOR);
        assertEquals(3001, testObject.getMonitoringCassandraReplicationFactor(8069));
    }

    @Test
    public void testGetMonitoringCassandraExpirationTimeout() throws Exception {
        doReturn(null).when(context).getInitParameter(AppConfig.MONITORING_CASSANDRA_EXPIRATION_TIMEOUT);
        assertEquals(8069, testObject.getMonitoringCassandraExpirationTimeout(8069));

        doReturn("" + 3001).when(context).getInitParameter(AppConfig.MONITORING_CASSANDRA_EXPIRATION_TIMEOUT);
        assertEquals(3001, testObject.getMonitoringCassandraExpirationTimeout(8069));
    }
}
