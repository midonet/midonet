/*
 * @(#)TestAppConfig        1.6 12/1/18
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.config;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import javax.servlet.ServletContext;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;

public class TestAppConfig {

    private ServletContext contextMock = null;
    private AppConfig appConfig = null;

    @Before
    public void setUp() throws Exception {
        this.contextMock = mock(ServletContext.class);
        appConfig = new AppConfig(this.contextMock);
    }

    @Test
    public void testGetVersionExists() throws Exception {
        doReturn("v1").when(contextMock).getInitParameter(AppConfig.versionKey);

        String result = appConfig.getVersion();

        Assert.assertEquals("v1", result);
    }

    @Test(expected = InvalidConfigException.class)
    public void testGetVersionNotExists() throws Exception {
        doReturn(null).when(contextMock).getInitParameter(AppConfig.versionKey);
        appConfig.getVersion();
    }

    @Test
    public void testGetDataStoreClassNameExists() throws Exception {
        doReturn("foo").when(contextMock).getInitParameter(
                AppConfig.dataStoreKey);

        String result = appConfig.getDataStoreClassName();

        Assert.assertEquals("foo", result);
    }

    @Test
    public void testGetDataStoreClassNameNotExists() throws Exception {
        doReturn(null).when(contextMock).getInitParameter(
                AppConfig.dataStoreKey);

        String result = appConfig.getDataStoreClassName();

        Assert.assertEquals(AppConfig.defaultDatatStore, result);
    }

    @Test
    public void testGetAuthorizerClassNameExists() throws Exception {
        doReturn("foo").when(contextMock).getInitParameter(
                AppConfig.authorizerKey);

        String result = appConfig.getAuthorizerClassName();

        Assert.assertEquals("foo", result);
    }

    @Test
    public void testGetAuthorizerClassNameNotExists() throws Exception {
        doReturn(null).when(contextMock).getInitParameter(
                AppConfig.authorizerKey);

        String result = appConfig.getAuthorizerClassName();

        Assert.assertEquals(AppConfig.defaultAuthorizer, result);
    }

    @Test
    public void testGetZkConnectionStringExists() throws Exception {
        doReturn("foo").when(contextMock).getInitParameter(
                AppConfig.zkConnStringKey);

        String result = appConfig.getZkConnectionString();

        Assert.assertEquals("foo", result);
    }

    @Test
    public void testGetZkConnectionStringNotExists() throws Exception {
        doReturn(null).when(contextMock).getInitParameter(
                AppConfig.zkConnStringKey);

        String result = appConfig.getZkConnectionString();

        Assert.assertEquals(AppConfig.defaultZkConnString, result);
    }

    @Test
    public void testGetZkTimeoutExists() throws Exception {
        doReturn("1000").when(contextMock).getInitParameter(
                AppConfig.zkTimeoutKey);

        int result = appConfig.getZkTimeout();

        Assert.assertEquals(1000, result);
    }

    @Test
    public void testGetZkTimeoutNotExists() throws Exception {
        doReturn(null).when(contextMock).getInitParameter(
                AppConfig.zkTimeoutKey);

        int result = appConfig.getZkTimeout();

        Assert.assertEquals(AppConfig.defaultZkTimeout, result);
    }

    @Test(expected = InvalidConfigException.class)
    public void testGetZkTimeoutBadValue() throws Exception {
        doReturn("foo").when(contextMock).getInitParameter(
                AppConfig.zkTimeoutKey);
        appConfig.getZkTimeout();
    }

    @Test
    public void testGetZkRootPathExists() throws Exception {
        doReturn("foo").when(contextMock).getInitParameter(AppConfig.zkRootKey);

        String result = appConfig.getZkRootPath();

        Assert.assertEquals("foo", result);
    }

    @Test
    public void testGetZkRootPathNotExists() throws Exception {
        doReturn(null).when(contextMock).getInitParameter(AppConfig.zkRootKey);

        String result = appConfig.getZkRootPath();

        Assert.assertEquals(AppConfig.defaultZkRootPath, result);
    }

    @Test
    public void testGetZkMgmtRootPathExists() throws Exception {
        doReturn("foo").when(contextMock).getInitParameter(
                AppConfig.zkMgmtRootKey);

        String result = appConfig.getZkMgmtRootPath();

        Assert.assertEquals("foo", result);
    }

    @Test
    public void testGetZkMgmtRootPathNotExists() throws Exception {
        doReturn(null).when(contextMock).getInitParameter(
                AppConfig.zkMgmtRootKey);

        String result = appConfig.getZkMgmtRootPath();

        Assert.assertEquals(AppConfig.defaultZkMgmtRootPath, result);
    }
}
